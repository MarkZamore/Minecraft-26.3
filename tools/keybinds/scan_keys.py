#!/usr/bin/env python3
"""Read vanilla defaults from the real named client, without launching Minecraft.

Requires a JDK providing javap. The snapshot and mod hashes are reviewed inputs
for the portable preset checks, which themselves need only Python in CI.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
import io
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
IRIS_SHA256 = "2260506334a17ac566ea30169ab491201208f55c1c7212bb987bc0c7c59a8d93"


def parse_iris_mappings(bytecode: str, native_names: dict) -> list[dict]:
    """Read the four actual OpenGL registrations, using the client's SDL names."""
    mappings = []
    constructors = re.finditer(
        r'new\s+[^\n]+// class net/minecraft/client/KeyMapping\s*\n'
        r'(?P<code>.*?)invokespecial[^\n]+KeyMapping\."<init>"[^\n]+', bytecode, re.S)
    for constructor in constructors:
        code = constructor.group("code")
        name = re.search(r"// String (iris\.keybind\.[^\n]+)", code)
        category = re.search(r"// Field (?:net/irisshaders/iris/IrisVKOnly\.)?irisKeybindCategory:", code)
        if not name or not category:
            raise ValueError("An Iris key registration changed; review its actual constructor")
        if "InputConstants.UNKNOWN:" in code:
            serialized = "key.keyboard.unknown"
        else:
            tail = code[code.index("InputConstants$Type.KEYBOARD:"):].splitlines()[1:]
            number = next((integer_constant(line) for line in tail if integer_constant(line) is not None), None)
            serialized = native_names.get(("KEYBOARD", number))
            if serialized is None:
                raise ValueError("An Iris SDL key has no native serialized name")
        mappings.append({"name": name.group(1).strip(), "default": serialized, "category": "IRIS"})
    return mappings


def nested_archives(data: bytes, origin: str):
    """Include nested mod classes; do not assume a wrapper is the only registry owner."""
    yield origin, data
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith(".jar"):
                yield from nested_archives(archive.read(name), origin + "!/" + name)


def integer_constant(line: str) -> int | None:
    match = re.search(r": (?:bipush|sipush)\s+(-?\d+)", line)
    if match:
        return int(match.group(1))
    match = re.search(r": iconst_(m1|\d+)\b", line)
    if match:
        return -1 if match.group(1) == "m1" else int(match.group(1))
    match = re.search(r"// int (-?\d+)\b", line)
    return int(match.group(1)) if match else None


def parse_native_names(bytecode: str) -> dict[tuple[str, int], str]:
    """Read the client's own serialized names; SDL and GLFW use different integers."""
    names = {}
    pattern = (r"getstatic[^\n]+// Field (?:[^\s:]+[./])?"
               r"(?P<kind>KEYBOARD|KEYSYM|SCANCODE|MOUSE):[^\n]+\n"
               r"[^\n]+// String (?P<name>key\.[^\n]+)\n"
               r"(?P<number>[^\n]+)\n[^\n]+// Method (?:[^\s]+\.)?addKey:")
    for match in re.finditer(pattern, bytecode):
        number = integer_constant(match.group("number"))
        key = (match.group("kind"), number)
        if number is None or key in names:
            raise ValueError("Unrecognized or duplicate native input registration")
        names[key] = match.group("name").strip()
    if not names or "key.keyboard.unknown" not in names.values():
        raise ValueError("Missing native input names")
    return names


def scan(client: Path, javap: str) -> dict:
    input_bytecode = subprocess.check_output(
        [javap, "-p", "-c", "-classpath", str(client), "com.mojang.blaze3d.platform.InputConstants$Type"], text=True)
    native_names = parse_native_names(input_bytecode)
    keyboard_kind = "KEYBOARD" if any(kind == "KEYBOARD" for kind, _ in native_names) else "KEYSYM"
    output = subprocess.check_output(
        [javap, "-p", "-c", "-classpath", str(client), "net.minecraft.client.Options"], text=True)
    constructors = re.finditer(
        r'new\s+[^\n]+// class net/minecraft/client/(?:KeyMapping|ToggleKeyMapping)\s*\n'
        r'(?P<code>.*?)invokespecial[^\n]+(?:KeyMapping|ToggleKeyMapping)\."<init>"[^\n]+',
        output, re.S)
    mappings = []
    for constructor in constructors:
        code = constructor.group("code")
        match = re.search(r"// String (key\.[^\n]+)", code)
        if not match:
            raise ValueError("A KeyMapping uses a dynamic name; extend the scanner before publishing")
        name = match.group(1).strip()
        tail = code[code.index("// String "):].splitlines()[1:]
        kind, number, serialized = keyboard_kind, None, None
        for line in tail:
            if "InputConstants$Type." in line:
                kind = line.split("InputConstants$Type.")[1].split(":")[0]
            if "InputConstants.UNKNOWN:" in line:
                serialized = "key.keyboard.unknown"
                break
            number = integer_constant(line)
            if number is not None:
                break
        if serialized is None and number is None:
            raise ValueError(f"No default key read for {name}")
        if serialized is None:
            serialized = native_names.get((kind, number))
            if serialized is None:
                raise ValueError(f"No native serialized input name for {name}: {kind}/{number}")
        category = re.search(r"KeyMapping\$Category\.([^:]+):", code)
        if category is None:
            raise ValueError(f"No category read for {name}")
        mappings.append({"name": name, "default": serialized, "category": category.group(1)})
    if not mappings or len({m["name"] for m in mappings}) != len(mappings):
        raise ValueError("Missing or duplicate client mappings")
    mods = {}
    iris_opengl, iris_vulkan = None, None
    import tempfile
    with tempfile.TemporaryDirectory() as temporary:
        for mod in sorted((REPO / "mods").glob("*.jar")):
            data = mod.read_bytes()
            mod_sha = hashlib.sha256(data).hexdigest()
            for origin, archive_data in nested_archives(data, mod.name):
                with zipfile.ZipFile(io.BytesIO(archive_data)) as archive:
                    referenced = [name for name in archive.namelist() if name.endswith(".class")
                                  and b"net/minecraft/client/KeyMapping" in archive.read(name)]
                inspected = mod
                if origin != mod.name:
                    inspected = Path(temporary) / (hashlib.sha256(archive_data).hexdigest() + ".jar")
                    inspected.write_bytes(archive_data)
                for name in referenced:
                    bytecode = subprocess.check_output(
                        [javap, "-p", "-c", "-classpath", str(inspected), name[:-6].replace("/", ".")], text=True)
                    if re.search(r'// Method [^\n]*(?:KeyMapping|ToggleKeyMapping)\."<init>"', bytecode):
                        if mod_sha != IRIS_SHA256 or origin != mod.name:
                            raise ValueError(f"{origin} constructs KeyMapping in {name}; extend registry scanning")
                        if name == "net/irisshaders/iris/Iris.class":
                            iris_opengl = parse_iris_mappings(bytecode, native_names)
                        elif name == "net/irisshaders/iris/IrisVKOnly.class":
                            iris_vulkan = parse_iris_mappings(bytecode, native_names)
                        else:
                            raise ValueError("Unreviewed Iris key constructor: " + name)
            mods[mod.name] = mod_sha
    if IRIS_SHA256 in mods.values():
        expected = {"iris.keybind.reload": "key.keyboard.r", "iris.keybind.toggleShaders": "key.keyboard.k",
                    "iris.keybind.shaderPackSelection": "key.keyboard.i", "iris.keybind.wireframe": "key.keyboard.unknown"}
        if (iris_opengl is None or len(iris_opengl) != 4 or
                {row["name"]: row["default"] for row in iris_opengl} != expected or
                iris_vulkan != [{"name": "iris.keybind.shaderPackSelection", "default": "key.keyboard.i", "category": "IRIS"}]):
            raise ValueError("Iris OpenGL/Vulkan registration branches changed")
        mappings.extend(iris_opengl)
    runtime = json.loads((REPO / "portable-pack.json").read_text(encoding="utf-8"))
    if runtime["loader"]["type"].lower() != "neoforge":
        raise ValueError("This scanner expects the NeoForge client")
    return {"minecraftVersion": runtime["minecraftVersion"], "neoForgeVersion": runtime["loader"]["version"],
            "clientSha256": hashlib.sha256(client.read_bytes()).hexdigest(),
            "modSha256": mods, "mappings": mappings}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", required=True, type=Path, help="actual named client jar used by the selected NeoForge profile")
    parser.add_argument("--javap", default=shutil.which("javap") or "javap")
    parser.add_argument("--verify", action="store_true", help="compare to snapshot without writing it")
    args = parser.parse_args()
    snapshot = scan(args.client, args.javap)
    target = HERE / "registered-keys.json"
    if args.verify:
        if json.loads(target.read_text(encoding="utf-8")) != snapshot:
            raise SystemExit("Client/mod registry differs; inspect and refresh registered-keys.json")
        print(f"Source verified: {len(snapshot['mappings'])} mappings including reviewed mod registrations")
    else:
        target.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"Wrote {len(snapshot['mappings'])} registered mappings")


if __name__ == "__main__":
    main()
