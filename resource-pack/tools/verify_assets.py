"""Read-only automated asset QA. Usage: python resource-pack/tools/verify_assets.py"""
from pathlib import Path
import json
import hashlib
import re
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "source"))
from pixel_art import ITEMS, STAGES, STRAINS, sprites
from build_assets import item_definition, manifest, plant_model, vanilla_definition
from import_vanilla import model_references


def verify():
    expected = sprites()
    catalog = json.loads((ROOT / "source/catalog.json").read_text(encoding="utf-8"))
    assert catalog == manifest(), "Catalog drift: rebuild resource-pack assets"
    assert len(expected) == 141
    for names, label in (([name for name, _, _ in ITEMS], "item icons"),
                         (["plant_" + stage for stage in STAGES], "growth stages"),
                         (["seed_" + sid for sid, _, _, _ in STRAINS], "seed packets")):
        assert len({expected[name].tobytes() for name in names}) == len(names), "Duplicate " + label
    item_config = (ROOT.parent / "verdantvice-paper/src/main/resources/items.yml").read_text(encoding="utf-8")
    enum = (ROOT.parent / "verdantvice-paper/src/main/java/com/verdantvice/security/CustomItemType.java").read_text(encoding="utf-8")
    types = set(re.findall(r"^    ([A-Z_]+)\((?:true|false)\)", enum, re.MULTILINE))
    assert types == {name.upper() for name, _, _ in ITEMS}, "Every CustomItemType needs artwork"
    carriers = {}
    plant_names = set()
    for name, carrier, number in ITEMS:
        key = name.replace("_", "-")
        pattern = rf"^{key}:\s*\{{\s*material:\s*{carrier.upper()},\s*custom-model-data:\s*{number}(?:\s*[,}}])"
        assert re.search(pattern, item_config, re.MULTILINE), "Item config mismatch: " + key
        carriers.setdefault(carrier, []).append((number, name))
    for index, stage in enumerate(STAGES):
        name = "plant_" + stage; plant_names.add(name)
        carriers.setdefault("amethyst_shard", []).append((12101 + index, name))
    for index, (sid, _, _, _) in enumerate(STRAINS):
        carriers["wheat_seeds"].append((13001 + index, "seed_" + sid))
        for ordinal, stage in enumerate(STAGES):
            name = f"plant_{stage}_{sid}"; plant_names.add(name)
            carriers["amethyst_shard"].append((13101 + index * 10 + ordinal, name))
    for version in ("1.21.4", "26.2"):
        base = ROOT / version
        references = json.loads((ROOT / f"source/vanilla/{version}/references.json").read_text(encoding="utf-8"))
        assert references["minecraft_version"] == version
        assert set(references["item_definitions_sha256"]) == set(carriers), "Vanilla carrier coverage drift"
        texture_dir = base / "assets/verdantvice/textures/item"
        model_dir = base / "assets/verdantvice/models/item"
        assert {p.stem for p in texture_dir.glob("*.png")} == set(expected), "Missing/obsolete textures"
        assert {p.stem for p in model_dir.glob("*.json")} == set(expected), "Missing/obsolete models"
        for name, source in expected.items():
            with Image.open(texture_dir / (name + ".png")) as image:
                assert image.mode == "RGBA" and image.size == (32, 32), name
                assert image.tobytes() == source.tobytes(), "Pixel source drift: " + name
                colors = image.getcolors(1024)
                assert len(colors) <= 25, "Palette too large: " + name
                assert {rgba[3] for _, rgba in colors} == {0, 255}, "Non-binary alpha: " + name
                assert image.getbbox() is not None, "Empty texture: " + name
                for x in range(32):
                    assert image.getpixel((x, 0))[3] == image.getpixel((x, 31))[3] == 0, name
                    assert image.getpixel((0, x))[3] == image.getpixel((31, x))[3] == 0, name
            actual_model = json.loads((model_dir / (name + ".json")).read_text(encoding="utf-8"))
            expected_model = plant_model(name) if name in plant_names else {
                "parent": "minecraft:item/generated", "textures": {"layer0": "verdantvice:item/" + name}}
            assert actual_model == expected_model, "Model geometry drift: " + name
        for carrier, entries in carriers.items():
            actual = json.loads((base / f"assets/minecraft/items/{carrier}.json").read_text(encoding="utf-8"))
            assert actual == item_definition(version, carrier, entries), "CustomModelData dispatch drift: " + carrier
            native_path = ROOT / f"source/vanilla/{version}/items/{carrier}.json"
            assert hashlib.sha256(native_path.read_bytes()).hexdigest() == references["item_definitions_sha256"][carrier]
            native = vanilla_definition(version, carrier)
            assert actual["model"]["fallback"] == native["model"], "Native item behavior lost: " + carrier
            for model in model_references(native):
                assert model in references["model_files_sha256"], "Missing vanilla model: " + model
        sounds = json.loads((base / "assets/verdantvice/sounds.json").read_text(encoding="utf-8"))
        assert set(sounds) == {"plant_ready", "tool", "process_complete"}
        for alias, definition in sounds.items():
            assert len(definition["sounds"]) == 1, alias
            event = definition["sounds"][0]
            assert event["type"] == "event", "Vanilla sounds must reference events, not guessed OGG paths"
            assert event["name"].removeprefix("minecraft:") in references["sound_events"], "Missing vanilla sound event"
        translations = list((ROOT / "source/lang").glob("*.json"))
        assert {path.stem for path in translations} == {"en_us", "pl_pl"}, "Missing subtitle translations"
        for translation in translations:
            authored = json.loads(translation.read_text(encoding="utf-8"))
            bundled = json.loads((base / "assets/verdantvice/lang" / translation.name).read_text(encoding="utf-8"))
            assert bundled == authored, "Subtitle translation drift: " + translation.name
        with Image.open(base / "pack.png") as cover:
            assert cover.size == (128, 128), "Pack cover dimensions"
            cover.verify()
    older, newer = ROOT / "1.21.4", ROOT / "26.2"
    old_files = {p.relative_to(older).as_posix() for p in older.rglob("*") if p.is_file()}
    new_files = {p.relative_to(newer).as_posix() for p in newer.rglob("*") if p.is_file()}
    assert old_files == new_files, "Version file sets differ"
    for path in old_files - {"pack.mcmeta"}:
        if path.startswith("assets/minecraft/items/"):
            # Each override wraps that release's imported native definition.
            continue
        assert (older / path).read_bytes() == (newer / path).read_bytes(), "Version mismatch: " + path
    assert json.loads((older / "pack.mcmeta").read_text(encoding="utf-8"))["pack"]["pack_format"] == 46
    metadata = json.loads((newer / "pack.mcmeta").read_text(encoding="utf-8"))["pack"]
    assert metadata["min_format"] == metadata["max_format"] == [88, 0]
    for name in ("all-items-preview.png", "all-growth-stages-preview.png"):
        with Image.open(ROOT / "source" / name) as image: image.verify()
    print("PASS: 25 item types, 12 seed variants, 8 generic + 96 variety growth stages; 141 textures/models × 2 packs.")
    print("PASS: source pixels, binary alpha, limited palette, cross-plane geometry, native client fallbacks, sound events, dispatch resets, version parity, previews.")


if __name__ == "__main__":
    verify()
