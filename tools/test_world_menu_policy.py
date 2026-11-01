"""The pack's required world-menu policy survives manifest/lock upgrades."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import generate_manifest


class WorldMenuPublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pack = self.root / "pack"
        (self.pack / "mods").mkdir(parents=True)
        self.source = self.pack / "tools/world-policy/contract.json"
        self.source.parent.mkdir(parents=True)
        self.contract = json.loads((Path(__file__).parent / "world-policy/contract.json").read_text())
        self.source.write_text(json.dumps(self.contract), encoding="utf-8")
        self.portable = {"schemaVersion": 1, "minecraftVersion": "26.3",
                         "loader": {"type": "neoforge", "version": "26.3.0.99"}}
        self.jar = self.pack / self.contract["artifactPath"]
        self.write_helper()

    def write_helper(self, *, mod_id=None, policy=None, project=None, version=None,
                     embedded=True, nested_spoof=False):
        metadata = ('modLoader="javafml"\nloaderVersion="[12,)"\n[[mods]]\n'
                    f'modId="{mod_id or self.contract["modId"]}"\n'
                    f'version="{version or self.contract["modVersion"]}"\n'
                    f'displayURL="{project or self.contract["projectUrl"]}"\n')
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("META-INF/neoforge.mods.toml", metadata)
            if embedded:
                archive.writestr(generate_manifest.WORLD_MENU_EMBEDDED_CONTRACT,
                                 json.dumps(self.contract if policy is None else policy))
            if nested_spoof:
                nested = io.BytesIO()
                with zipfile.ZipFile(nested, "w") as inner:
                    inner.writestr("META-INF/neoforge.mods.toml",
                                   '[[mods]]\nmodId="lanmc_world_menu"\nversion="1.0.0"\n')
                archive.writestr("META-INF/jarjar/helper.jar", nested.getvalue())
                archive.writestr("META-INF/jarjar/metadata.json", json.dumps({"jars": [{
                    "path": "META-INF/jarjar/helper.jar"}]}))
        self.refresh_lock()

    def refresh_lock(self):
        (self.pack / "portable-pack.json").write_text(json.dumps(self.portable), encoding="utf-8")
        files = []
        for jar in sorted((self.pack / "mods").glob("*.jar")):
            data = jar.read_bytes()
            ids = sorted({mod["id"] for mod in generate_manifest.read_mods(
                data, jar.name, loader="neoforge") if mod["isLoadedMod"]})
            files.append({"path": jar.relative_to(self.pack).as_posix(),
                          "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                          "modIds": ids})
        lock = {**self.portable, "files": files}
        (self.pack / "tools/distribution-lock.json").write_text(json.dumps(lock), encoding="utf-8")
        # Establish that the ordinary exact-hash/ID lock is internally valid;
        # each negative below must be caught by the independent policy guard.
        shipped = [row["path"] for row in files]
        meta = {row["path"]: (row["sha256"], row["sizeBytes"]) for row in files}
        generate_manifest.validate_mod_lock(self.pack, self.portable, shipped, meta)

    def generate(self):
        out = self.root / "dist"
        out.mkdir(exist_ok=True)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            generate_manifest.generate(self.pack, out, "policy-test")
        return json.loads((out / "pack-manifest.json").read_text())

    def assert_refused(self):
        with self.assertRaises(SystemExit):
            self.generate()
        self.assertFalse((self.root / "dist/pack-manifest.json").exists())

    def test_required_policy_is_carried_by_current_and_future_runtime_descriptors(self):
        for game, loader in (("26.3", "26.3.0.99"), ("26.4", "26.4.0.1")):
            with self.subTest(minecraft=game):
                self.portable["minecraftVersion"] = game
                self.portable["loader"]["version"] = loader
                self.refresh_lock()
                manifest = self.generate()
                self.assertEqual(game, manifest["minecraftVersion"])
                self.assertIn(self.contract["artifactPath"], [row["path"] for row in manifest["files"]])
                self.assertEqual(1, manifest["modCount"])

    def test_removing_helper_and_its_lock_row_together_still_refuses_publication(self):
        self.jar.unlink()
        self.refresh_lock()
        self.assert_refused()

    def test_renaming_helper_with_an_updated_consistent_lock_refuses_publication(self):
        self.jar.rename(self.jar.with_name("another-name.jar"))
        self.refresh_lock()
        self.assert_refused()

    def test_wrong_root_mod_id_is_not_rescued_by_a_matching_nested_mod(self):
        self.write_helper(mod_id="unrelated", nested_spoof=True)
        self.assert_refused()

    def test_wrong_embedded_policy_key_screen_side_or_source_refuses_publication(self):
        mutations = {"translationKey": "menu.options", "screen": "net.minecraft.client.gui.screens.Screen",
                     "closeAfterInitScreen": "net.minecraft.client.gui.screens.options.OptionsScreen",
                     "clientOnly": False, "policyId": "another-policy",
                     "sourceProject": "someone/another-project"}
        for field, value in mutations.items():
            with self.subTest(field=field):
                self.write_helper(policy={**self.contract, field: value})
                self.assert_refused()

    def test_missing_after_init_policy_cannot_publish_a_pause_button_only_component(self):
        incomplete = {key: value for key, value in self.contract.items() if key != "closeAfterInitScreen"}
        self.write_helper(policy=incomplete)
        self.assert_refused()

    def test_missing_embedded_contract_refuses_publication(self):
        self.write_helper(embedded=False)
        self.assert_refused()

    def test_different_root_mod_version_or_project_refuses_publication(self):
        for overrides in ({"version": "0.0.0"}, {"project": "https://github.com/someone/another-project"}):
            with self.subTest(overrides=overrides):
                self.write_helper(**overrides)
                self.assert_refused()

    def test_changing_both_source_and_embedded_contract_cannot_change_the_required_policy(self):
        changed = {**self.contract, "translationKey": "menu.options"}
        self.source.write_text(json.dumps(changed), encoding="utf-8")
        self.write_helper(policy=changed)
        self.assert_refused()

    def test_client_only_contract_cannot_be_replaced_with_an_integer(self):
        self.source.write_text(json.dumps({**self.contract, "clientOnly": 1}), encoding="utf-8")
        self.assert_refused()

    def test_different_loader_cannot_silently_skip_the_policy(self):
        self.portable["loader"] = {"type": "fabric", "version": "0.19.3"}
        self.refresh_lock()
        self.assert_refused()

    def test_generic_fixture_without_a_policy_contract_keeps_working(self):
        self.source.unlink()
        self.jar.unlink()
        self.refresh_lock()
        manifest = self.generate()
        self.assertEqual(0, manifest["modCount"])


if __name__ == "__main__":
    unittest.main()
