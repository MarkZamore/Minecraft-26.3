import json
import unittest
from pathlib import Path

import check_preset
import scan_keys


class PresetValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry = json.loads((check_preset.HERE / "registered-keys.json").read_text(encoding="utf-8"))
        cls.allowances = json.loads((check_preset.HERE / "allowed-conflicts.json").read_text(encoding="utf-8"))["pairs"]
        cls.mappings = check_preset.parse((check_preset.REPO / "launcher/controls-preset.txt").read_text(encoding="utf-8"))

    def test_complete_vanilla_layout_passes(self):
        overlaps, problems = check_preset.validate(self.mappings, self.registry, self.allowances)
        self.assertEqual([], problems)
        self.assertEqual(len(self.allowances), overlaps)
        self.assertEqual(66, len(self.mappings))
        self.assertEqual("key.keyboard.j", self.mappings["key_iris.keybind.shaderPackSelection"])

    def test_duplicate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            check_preset.parse("key_key.jump:key.keyboard.space\nkey_key.jump:key.keyboard.a")

    def test_unreviewed_modifier_is_rejected(self):
        with self.assertRaises(ValueError):
            check_preset.parse("key_key.jump:key.keyboard.a:SHIFT")

    def test_missing_registration_is_rejected(self):
        mappings = dict(self.mappings)
        del mappings["key_key.jump"]
        self.assertTrue(any("Missing registered" in p for p in check_preset.validate(mappings, self.registry, self.allowances)[1]))

    def test_new_collision_does_not_inherit_vanilla_allowance(self):
        mappings = dict(self.mappings)
        mappings["key_key.jump"] = "key.keyboard.w"
        self.assertTrue(any("Unreviewed conflict" in p for p in check_preset.validate(mappings, self.registry, self.allowances)[1]))

    def test_changed_pair_key_does_not_inherit_vanilla_allowance(self):
        mappings = dict(self.mappings)
        mappings["key_key.debug.overlay"] = "key.keyboard.f10"
        mappings["key_key.debug.modifier"] = "key.keyboard.f10"
        problems = check_preset.validate(mappings, self.registry, self.allowances)[1]
        self.assertTrue(any("Unreviewed conflict" in p for p in problems))
        self.assertTrue(any("Stale conflict" in p for p in problems))

    def test_unregistered_mapping_is_rejected(self):
        mappings = dict(self.mappings)
        mappings["key_missing.mod.action"] = "key.keyboard.unknown"
        self.assertTrue(any("Unregistered" in p for p in check_preset.validate(mappings, self.registry, self.allowances)[1]))

    def test_sdl_names_come_from_client_not_glfw_integer_table(self):
        bytecode = '''
  0: getstatic #1 // Field KEYBOARD:Lcom/mojang/blaze3d/platform/InputConstants$Type;
  3: ldc #2 // String key.keyboard.unknown
  5: iconst_0
  6: invokestatic #3 // Method addKey:(Lcom/mojang/blaze3d/platform/InputConstants$Type;Ljava/lang/String;I)V
  9: getstatic #1 // Field KEYBOARD:Lcom/mojang/blaze3d/platform/InputConstants$Type;
 12: ldc #4 // String key.keyboard.w
 14: bipush 26
 16: invokestatic #3 // Method addKey:(Lcom/mojang/blaze3d/platform/InputConstants$Type;Ljava/lang/String;I)V
 19: getstatic #5 // Field MOUSE:Lcom/mojang/blaze3d/platform/InputConstants$Type;
 22: ldc #6 // String key.mouse.left
 24: iconst_1
 25: invokestatic #3 // Method addKey:(Lcom/mojang/blaze3d/platform/InputConstants$Type;Ljava/lang/String;I)V
 28: getstatic #5 // Field MOUSE:Lcom/mojang/blaze3d/platform/InputConstants$Type;
 31: ldc #7 // String key.mouse.right
 33: iconst_3
 34: invokestatic #3 // Method addKey:(Lcom/mojang/blaze3d/platform/InputConstants$Type;Ljava/lang/String;I)V
'''
        names = scan_keys.parse_native_names(bytecode)
        self.assertEqual("key.keyboard.w", names["KEYBOARD", 26])
        self.assertEqual("key.mouse.left", names["MOUSE", 1])
        self.assertEqual("key.mouse.right", names["MOUSE", 3])
        self.assertEqual("key.keyboard.unknown", names["KEYBOARD", 0])


if __name__ == "__main__":
    unittest.main()
