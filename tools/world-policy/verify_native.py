#!/usr/bin/env python3
"""Execute the pack policy against official Minecraft/NeoForge classes, without a game.

The probe allocates a PauseScreen without its Minecraft-dependent constructor,
then uses native widgets, native Screen methods and the real Init.Post event.
It does not invoke a client/server main, a renderer, a world or a mod loader.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import tomllib
import zipfile

import build


HERE = Path(__file__).resolve().parent
MARKER = "NATIVE_WORLD_MENU_PASS "


def check_jar(jar: Path, inputs: build.BuildInputs) -> None:
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("The component JAR contains duplicate entries")
        classes = [name for name in names if name.endswith(".class")]
        if classes != ["lanmc/worldmenu/WorldMenuPolicy.class"]:
            raise ValueError("The component must contain only its own policy class")
        metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
        if metadata.get("modLoader") != "javafml" or metadata.get("license") != "MIT":
            raise ValueError("Unexpected mod loader or component license")
        mods = metadata.get("mods", [])
        if len(mods) != 1 or mods[0].get("modId") != "lanmc_world_menu":
            raise ValueError("The component has an unexpected mod identity")
        dependencies = metadata.get("dependencies", {}).get("lanmc_world_menu", [])
        by_id = {item.get("modId"): item for item in dependencies}
        if len(by_id) != 2 or len(dependencies) != 2:
            raise ValueError("Expected exactly Minecraft and NeoForge dependencies")
        for mod_id, version in (("minecraft", inputs.minecraft_version), ("neoforge", inputs.neoforge_version)):
            item = by_id.get(mod_id, {})
            if item.get("side") != "CLIENT" or item.get("type") != "required" or item.get("versionRange") != f"[{version},)":
                raise ValueError("The component dependency does not match its reviewed client runtime")


def verify(pack: Path, jar: Path, cache: Path, report_path: Path | None,
           minecraft: str | None = None, neoforge: str | None = None) -> dict:
    inputs = build.resolve_inputs(pack, cache, minecraft, neoforge)
    check_jar(jar, inputs)
    sources = sorted((HERE / "probe").rglob("*.java"))
    if not sources:
        raise ValueError("The mandatory native probe source is missing")
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="native-probe-", dir=cache) as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classpath = [jar, *inputs.classpath]
        build.compile_java(sources, classpath, classes, inputs.java_version)
        args = ["-Xverify:all", "-Djava.awt.headless=true", f"-Duser.home={work / 'home'}",
                "-classpath", os.pathsep.join(str(item.resolve()) for item in [classes, *classpath]),
                "lanmc.worldmenu.probe.NativeWorldMenuProbe", str(jar.resolve()),
                *(str(item) for item in (*inputs.resource_format, *inputs.data_format))]
        argument_file = work / "java.args"
        argument_file.write_text("\n".join('"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"' for value in args), encoding="utf-8")
        result = subprocess.run([build.jdk_tool("java"), "@" + str(argument_file)], cwd=work,
                                text=True, encoding="utf-8", errors="replace", capture_output=True, timeout=120)
        log_path = cache / "native-probe.log"
        log_path.write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
        if result.returncode:
            raise RuntimeError(f"Native policy probe failed (exit {result.returncode}); see {log_path}\n" + result.stderr[-6000:])
        proof_lines = [line.split(MARKER, 1)[1] for line in result.stdout.splitlines() if MARKER in line]
        if len(proof_lines) != 1:
            raise RuntimeError(f"Native probe produced no unique success record; see {log_path}")
        proof = json.loads(proof_lines[0])
    report = {"status": "PASS", "minecraft": inputs.minecraft_version,
              "neoforge": inputs.neoforge_version, "java": inputs.java_version,
              "jarSha256": build.digest(jar), "jarSizeBytes": jar.stat().st_size,
              "nativeProof": proof,
              "scope": "Official native classes and event object, direct handler call; no game main, renderer, event bus discovery, client restart or user world."}
    if report_path is not None:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, default=build.PACK_ROOT)
    parser.add_argument("--jar", "--jar-path", dest="jar", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--minecraft")
    parser.add_argument("--neoforge")
    args = parser.parse_args()
    pack = args.pack.resolve()
    cache = (args.cache_dir or pack / ".cache" / "world-policy").resolve()
    print(json.dumps(verify(pack, args.jar.resolve(), cache, args.report, args.minecraft, args.neoforge), indent=2))


if __name__ == "__main__":
    main()
