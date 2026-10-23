"""Refresh small vanilla references from already-installed official clients.

Usage: python resource-pack/tools/import_vanilla.py --minecraft-directory PATH
No game binaries, model textures, or sound files are copied into this project.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "source"))
from pixel_art import ITEMS

VERSIONS = ("1.21.4", "26.2")
SOUND_EVENTS = ("block.azalea_leaves.place", "item.brush.brushing.generic", "block.amethyst_block.chime")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def model_references(value):
    if isinstance(value, dict):
        if value.get("type") == "minecraft:model":
            yield value["model"]
        elif value.get("type") == "minecraft:special":
            yield value["base"]
        for child in value.values():
            yield from model_references(child)
    elif isinstance(value, list):
        for child in value:
            yield from model_references(child)


def import_version(minecraft, version):
    version_dir = minecraft / "versions" / version
    version_metadata = json.loads((version_dir / f"{version}.json").read_text(encoding="utf-8"))
    client_path = version_dir / f"{version}.jar"
    expected_client_hash = version_metadata["downloads"]["client"]["sha1"]
    with client_path.open("rb") as stream:
        assert hashlib.file_digest(stream, "sha1").hexdigest() == expected_client_hash, "Client JAR checksum mismatch"
    asset_id = version_metadata["assetIndex"]["id"]
    index = json.loads((minecraft / "assets/indexes" / f"{asset_id}.json").read_text(encoding="utf-8"))
    sound_hash = index["objects"]["minecraft/sounds.json"]["hash"]
    sound_bytes = (minecraft / "assets/objects" / sound_hash[:2] / sound_hash).read_bytes()
    assert hashlib.sha1(sound_bytes).hexdigest() == sound_hash, "Installed sound manifest checksum mismatch"
    sounds = json.loads(sound_bytes)
    carriers = sorted({carrier for _, carrier, _ in ITEMS} | {"amethyst_shard"})
    output = ROOT / "source/vanilla" / version
    models, definitions = {}, {}
    with ZipFile(client_path) as client:
        for carrier in carriers:
            path = f"assets/minecraft/items/{carrier}.json"
            raw = client.read(path)
            definition = json.loads(raw)
            write_json(output / "items" / f"{carrier}.json", definition)
            definitions[carrier] = hashlib.sha256(
                (output / "items" / f"{carrier}.json").read_bytes()).hexdigest()
            for reference in model_references(definition):
                namespace, name = reference.split(":", 1)
                model_path = f"assets/{namespace}/models/{name}.json"
                models[reference] = hashlib.sha256(client.read(model_path)).hexdigest()
    selected_sounds = {event: sounds[event] for event in SOUND_EVENTS}
    sound_files = {}
    for event, definition in selected_sounds.items():
        for sound in definition["sounds"]:
            if isinstance(sound, dict):
                assert sound.get("type", "file") == "file", f"Unexpected nested sound event: {event}"
                sound = sound["name"]
            sound = sound.removeprefix("minecraft:")
            key = f"minecraft/sounds/{sound}.ogg"
            sound_files[key] = index["objects"][key]["hash"]
    write_json(output / "references.json", {
        "minecraft_version": version,
        "source": "Official installed Minecraft client item definitions and asset index",
        "official_client_sha1": expected_client_hash,
        "asset_index": asset_id,
        "sound_manifest_sha1": sound_hash,
        "item_definitions_sha256": definitions,
        "model_files_sha256": dict(sorted(models.items())),
        "sound_events": selected_sounds,
        "sound_files_sha1": dict(sorted(sound_files.items())),
    })
    print(f"Imported {version}: {len(carriers)} item definitions, {len(models)} model references, "
          f"{len(selected_sounds)} verified sound events.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-directory", required=True, type=Path)
    options = parser.parse_args()
    for version in VERSIONS:
        import_version(options.minecraft_directory, version)
