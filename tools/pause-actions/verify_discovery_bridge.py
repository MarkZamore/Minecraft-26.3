#!/usr/bin/env python3
"""Replay the packaged notification bridge through an actual launcher's hook JAR.

The hook JAR is appended to bootstrap search as in the real agent. Only in-memory
Minecraft/Steam service boundaries are replaced; no game, network or Steam API is
started. This is supplemental to verify_native.py's actual native UI/Mixin proof.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

import build

HERE = Path(__file__).resolve().parent
MARKER = "DISCOVERY_BRIDGE_PASS "
BRIDGE = "lanmc/pauseactions/SteamWorldDiscovery.class"
HOOK = "minecraft/portable/identity/PortableSteamDiscoveryHooks.class"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify(pack: Path, jar: Path, agent: Path, cache: Path, report: Path | None) -> dict:
    inputs = build.resolve_inputs(pack, cache)
    # Keep the native game JARs off this fixture classpath. Only genuine logging
    # dependencies are needed to initialize the actual packaged bridge class.
    logging = []
    for entry in inputs.classpath:
        if entry.name.startswith(("logging-", "slf4j-api-")):
            logging.append(entry)
    if len(logging) != 2:
        raise ValueError("Expected genuine Mojang logging and SLF4J API dependencies")
    with zipfile.ZipFile(jar) as archive:
        bridge_bytes = archive.read(BRIDGE)
    with zipfile.ZipFile(agent) as archive:
        hooks_bytes = archive.read("portable-identity-hooks.jar")
    sources = sorted((HERE / "integration").rglob("*.java"))
    if not sources:
        raise ValueError("Mandatory bridge integration fixtures are absent")
    proofs = []
    with tempfile.TemporaryDirectory(prefix="discovery-bridge-", dir=cache) as temporary:
        work = Path(temporary)
        hooks = work / "actual-agent-hooks.jar"
        hooks.write_bytes(hooks_bytes)
        with zipfile.ZipFile(hooks) as archive:
            hook_sha = sha256(archive.read(HOOK))
        classes = work / "classes"
        build.compile_java(sources, [jar, *logging], classes, inputs.java_version)
        # The second fixture version is deliberately different. Matching that
        # resource/property tests absence of a transport pin in the PACK bridge;
        # it does not certify an unknown author's future transport interface.
        for version in ["0.3.4", "later-fixture-version"]:
            (classes / "e4steam-version.properties").write_text("version=" + version + "\n", encoding="utf-8")
            arguments = ["-Xverify:all", "-Djava.awt.headless=true",
                         "-Xbootclasspath/a:" + str(hooks.resolve()),
                         "-classpath", os.pathsep.join(str(path.resolve()) for path in [classes, jar, *logging]),
                         "proof.bridge.DiscoveryBridgeProbe", version, inputs.minecraft_version,
                         sha256(bridge_bytes), hook_sha]
            argument_file = work / "java.args"
            argument_file.write_text("\n".join('"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
                                                for value in arguments), encoding="utf-8")
            result = subprocess.run([build.jdk_tool("java"), "@" + str(argument_file)], cwd=work,
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
            log = cache / ("bridge-" + version + ".log")
            log.write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
            rows = [line.split(MARKER, 1)[1] for line in result.stdout.splitlines() if MARKER in line]
            if result.returncode or len(rows) != 1:
                raise RuntimeError(f"Actual bridge integration failed; see {log}\n"
                                   + result.stdout[-3000:] + result.stderr[-2000:])
            proofs.append({"transportResourceAndProperty": version, **json.loads(rows[0])})
    proof = {"status": "PASS", "minecraft": inputs.minecraft_version, "neoforge": inputs.neoforge_version,
             "java": inputs.java_version, "packJarSha256": build.digest(jar),
             "packBridgeClassSha256": sha256(bridge_bytes), "agentSha256": build.digest(agent),
             "embeddedHooksSha256": sha256(hooks_bytes), "hookClassSha256": hook_sha,
             "loggingLibraries": [{"name": path.name, "sha256": build.digest(path)} for path in logging],
             "executions": proofs,
             "scope": "Actual packaged bridge plus actual installed bootstrap publisher/readWorlds; explicit "
                      "in-memory Minecraft/Steam service boundaries. No game main, renderer, native Steam, "
                      "socket, world or real peer. Changed fixture version proves no PACK bridge pin, "
                      "not compatibility with an unknown future transport interface."}
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(proof, indent=2) + "\n", encoding="utf-8")
    return proof


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, default=build.PACK_ROOT)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--agent", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, default=build.PACK_ROOT / ".cache" / "pause-actions")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.pack.resolve(), args.jar.resolve(), args.agent.resolve(),
                            args.cache_dir.resolve(), args.report), indent=2))


if __name__ == "__main__":
    main()
