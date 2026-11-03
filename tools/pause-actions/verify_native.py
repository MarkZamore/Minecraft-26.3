#!/usr/bin/env python3
"""Run the actual packaged Mixin accessor and pause actions against official APIs.

No client/server main, renderer or user world is started. Native classes are used
with explicit recording boundaries for network publication and UI feedback.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import zipfile
import build

HERE = Path(__file__).resolve().parent
MARKER = "ACTUAL_MIXIN_BUTTON_ACCESSOR_PASS "


def verify(pack: Path, jar: Path, cache: Path, report: Path | None = None) -> dict:
    inputs = build.resolve_inputs(pack, cache)
    button_entry = 'net/minecraft/client/gui/components/Button.class'
    native_client = None
    button_bytes = None
    for candidate in inputs.classpath:
        if not candidate.is_file() or not zipfile.is_zipfile(candidate):
            continue
        with zipfile.ZipFile(candidate) as native_archive:
            if button_entry in native_archive.namelist():
                native_client = candidate.resolve()
                button_bytes = native_archive.read(button_entry)
                break
    if native_client is None or button_bytes is None:
        raise ValueError('Actual native Button provider is absent from the resolved classpath')
    vanilla = (cache / 'versions' / inputs.minecraft_version / 'client.jar').resolve()
    if native_client == vanilla:
        raise ValueError('Native proof requires the NeoForge patched client before the raw vanilla fallback')
    button_sha = hashlib.sha256(button_bytes).hexdigest()
    if (inputs.minecraft_version, inputs.neoforge_version) == ('26.3', '26.3.0.99') and button_sha != 'a3c5bf37aac82e360913080768fdf2005506afb9a0124e3466ee0a4d1bf6de7a':
        raise ValueError('Button bytes differ from the verified actual NeoForge 26.3.0.99 runtime target')
    native_input = {'clientJarName': native_client.name, 'clientJarSha256': build.digest(native_client),
                    'buttonClassSha256': button_sha, 'buttonClassSizeBytes': len(button_bytes)}
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or any(name.endswith('.class') and not name.startswith('lanmc/pauseactions/') for name in names):
            raise ValueError('Component contains duplicate entries or foreign classes')
        config = json.loads(archive.read('lanmc-pause-actions.mixins.json'))
        if config.get('required') is not True or config.get('package') != 'lanmc.pauseactions.mixin' or config.get('client') != ['ButtonActionAccessor'] or config.get('mixins'):
            raise ValueError('Expected a mandatory client-only production accessor configuration')
        metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
        if metadata.get('mixins') != [{'config': 'lanmc-pause-actions.mixins.json'}]:
            raise ValueError('The packaged Mixin config is not declared in native NeoForge metadata')
        if metadata.get('modLoader') != 'javafml' or metadata.get('license') != 'MIT' or metadata.get('mods', [{}])[0].get('modId') != 'lanmc_pause_actions':
            raise ValueError('Unexpected mod identity, loader or license')
        for dependency, version in [('minecraft', inputs.minecraft_version), ('neoforge', inputs.neoforge_version)]:
            matches = [item for item in metadata.get('dependencies', {}).get('lanmc_pause_actions', []) if item.get('modId') == dependency]
            if len(matches) != 1 or matches[0].get('side') != 'CLIENT' or matches[0].get('versionRange') != f'[{version},)' or matches[0].get('type') != 'required':
                raise ValueError('Unexpected required client runtime dependency')
        archive.read('META-INF/LICENSE.txt')
    sources = sorted((HERE / 'probe').rglob('*.java'))
    if not sources:
        raise ValueError('Mandatory native probe sources are missing')
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='native-mixin-', dir=cache) as temporary:
        work = Path(temporary)
        classes = work / 'classes'
        classpath = [jar, *inputs.classpath]
        build.compile_java(sources, classpath, classes, inputs.java_version)
        shutil.copytree(HERE / 'probe' / 'resources', classes, dirs_exist_ok=True)
        runtime_args = ['-Xverify:all', '-Djava.awt.headless=true', f'-Duser.home={work / "home"}',
                        f'-Dpauseactions.probe.minecraftVersion={inputs.minecraft_version}',
                        f'-Dpauseactions.probe.neoforgeVersion={inputs.neoforge_version}',
                        '-Dmixin.service=proof.host.OfflineMixinService', '-classpath',
                        os.pathsep.join(str(path.resolve()) for path in [classes, *classpath]),
                        'proof.host.MixinEngineProbe', str(work / 'result'), str(jar.resolve()),
                        *map(str, (*inputs.resource_format, *inputs.data_format))]
        argument_file = work / 'java.args'
        argument_file.write_text('\n'.join('"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"' for value in runtime_args), encoding='utf-8')
        result = subprocess.run([build.jdk_tool('java'), '@' + str(argument_file)], cwd=work,
                                text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=120)
        log = cache / 'native-probe.log'
        log.write_text(result.stdout + '\n' + result.stderr, encoding='utf-8')
        if result.returncode:
            raise RuntimeError(f'Native Mixin/action probe failed ({result.returncode}); see {log}\n' + result.stdout[-4500:] + result.stderr[-2000:])
        records = [line.split(MARKER, 1)[1] for line in result.stdout.splitlines() if MARKER in line]
        if len(records) != 1:
            raise RuntimeError(f'Native proof did not produce a unique PASS record; see {log}')
        native = json.loads(records[0])
        if native.get('nativeClassOriginalSha256') != button_sha or native.get('nativeClassOriginalBytes') != len(button_bytes):
            raise RuntimeError('Mixin engine transformed a different Button source from the verified patched-client provider')
        shutil.copy2(work / 'result' / 'Button.mixin-transformed.class', cache / 'Button.mixin-transformed.class')
        # A separate JVM with the test helper physically absent proves that
        # missing/older launcher support cannot prevent native hosting/UI feedback.
        helper = classes / 'minecraft/portable/identity/PortableSteamDiscoveryHooks.class'
        if not helper.is_file():
            raise RuntimeError('Mandatory optional-discovery recorder fixture is missing')
        hidden_helper = helper.with_suffix('.excluded')
        helper.replace(hidden_helper)
        try:
            absent_args = [*runtime_args, 'discovery-helper-absent']
            argument_file.write_text('\n'.join('"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"' for value in absent_args), encoding='utf-8')
            absent = subprocess.run([build.jdk_tool('java'), '@' + str(argument_file)], cwd=work,
                                    text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=120)
        finally:
            hidden_helper.replace(helper)
        (cache / 'native-missing-discovery-helper.log').write_text(absent.stdout + '\n' + absent.stderr, encoding='utf-8')
        absent_records = [line.split(MARKER, 1)[1] for line in absent.stdout.splitlines() if MARKER in line]
        if absent.returncode or len(absent_records) != 1:
            raise RuntimeError('Native publication did not survive an absent optional discovery helper\n' + absent.stdout[-4500:] + absent.stderr[-2000:])
        absent_proof = json.loads(absent_records[0])
        if absent_proof.get('nativeClassOriginalSha256') != button_sha:
            raise RuntimeError('Missing-helper proof used a different native Button target')
        native['missingDiscoveryHelperProof'] = absent_proof['execution']
        negative_proofs = []
        for case in ['missing-field', 'wrong-field-type']:
            fault_args = [*runtime_args, case]
            argument_file.write_text('\n'.join('"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"' for value in fault_args), encoding='utf-8')
            fault = subprocess.run([build.jdk_tool('java'), '@' + str(argument_file)], cwd=work,
                                   text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=60)
            (cache / f'native-negative-{case}.log').write_text(fault.stdout + '\n' + fault.stderr, encoding='utf-8')
            rejected = [line.split('ACTUAL_MIXIN_NEGATIVE_REJECTED ', 1)[1] for line in fault.stdout.splitlines() if 'ACTUAL_MIXIN_NEGATIVE_REJECTED ' in line]
            if fault.returncode or len(rejected) != 1:
                raise RuntimeError(f'Actual Mixin did not reject malformed required target field: {case}\n' + fault.stdout[-3000:] + fault.stderr[-1000:])
            negative_proofs.append(json.loads(rejected[0]))
        native['requiredAccessorNegativeCases'] = negative_proofs
    proof = {'status': 'PASS', 'minecraft': inputs.minecraft_version, 'neoforge': inputs.neoforge_version,
             'java': inputs.java_version, 'jarSha256': build.digest(jar), 'jarSizeBytes': jar.stat().st_size,
             'nativeProof': native,
             'nativeRuntimeInput': native_input,
             'scope': 'Actual packaged accessor/config and Sponge Mixin engine; actual native button, events and published-state getter. Explicit network/UI-feedback boundaries; no game main, renderer, event bus discovery or user world.'}
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(proof, indent=2) + '\n', encoding='utf-8')
    return proof


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack', type=Path, default=build.PACK_ROOT)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--cache-dir', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    pack = args.pack.resolve()
    print(json.dumps(verify(pack, args.jar.resolve(), (args.cache_dir or pack / '.cache' / 'pause-actions').resolve(), args.report), indent=2))


if __name__ == '__main__':
    main()
