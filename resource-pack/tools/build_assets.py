"""Reproducibly build editable pixel art: python resource-pack/tools/build_assets.py [pack-root]."""
from __future__ import annotations
import json
import sys
from copy import deepcopy
from collections import defaultdict
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "source"))
from pixel_art import ITEMS, P, STAGES, STRAINS, fan, Canvas, sprites

def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

def vanilla_definition(version, carrier):
    # These small definitions are imported from the matching official client.
    # Block items, clocks, brushes, bundles and special renderers are not plain
    # minecraft:item/<material> models. Preserve their entire native behavior.
    return json.loads((ROOT / f"source/vanilla/{version}/items/{carrier}.json").read_text(encoding="utf-8"))

def item_definition(version, carrier, entries):
    # range_dispatch picks the preceding threshold. Reset after each owned range,
    # so other plugins' integer model IDs retain the vanilla appearance.
    native = vanilla_definition(version, carrier)
    fallback = native["model"]
    table = {number: {"type": "minecraft:model", "model": "verdantvice:item/" + name}
             for number, name in entries}
    for number, _ in entries:
        if number + 1 not in table: table[number + 1] = fallback
    result = deepcopy(native)
    result["model"] = {"type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data",
                       "index": 0, "entries": [{"threshold": number, "model": model}
                                                for number, model in sorted(table.items())],
                       "fallback": fallback}
    return result

def plant_model(name):
    face = {"uv": [0, 0, 16, 16], "texture": "#cross", "tintindex": -1}
    elements = [{"from": [0.5, 0, 8], "to": [15.5, 16, 8],
                 "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": angle, "rescale": True},
                 "shade": False, "faces": {"north": face, "south": face}} for angle in (-45, 45)]
    return {"ambientocclusion": False, "textures": {"cross": "verdantvice:item/" + name,
                                                     "particle": "verdantvice:item/" + name},
            "elements": elements,
            "display": {"gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
                        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
                        "ground": {"translation": [0, 2, 0], "scale": [.5, .5, .5]},
                        "head": {"translation": [0, 0, 0], "scale": [1, 1, 1]}}}

def manifest():
    items = [{"id": name.replace("_", "-"), "type": name.upper(), "material": carrier.upper(),
              "custom_model_data": number, "model": name} for name, carrier, number in ITEMS]
    strains = []
    for index, (sid, display, accent, light) in enumerate(STRAINS):
        strains.append({"id": sid, "display_name": display, "index": index,
                        "accent": accent, "highlight": light, "seed_model_data": 13001 + index,
                        "seed_model": "seed_" + sid,
                        "stages": [{"stage": stage.upper(), "custom_model_data": 13101 + index * 10 + ordinal,
                                    "model": f"plant_{stage}_{sid}"} for ordinal, stage in enumerate(STAGES)]})
    return {"texture_size": 32, "art_revision": 2, "items": items, "plant_material": "AMETHYST_SHARD",
            "generic_stages": [{"stage": stage.upper(), "custom_model_data": 12101 + index,
                                "model": "plant_" + stage} for index, stage in enumerate(STAGES)], "strains": strains}

def font(size):
    for path in ("C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if Path(path).exists(): return ImageFont.truetype(path, size)
    return ImageFont.load_default()

def preview(output, textures, catalog):
    entries = [(e["model"], e["id"]) for e in catalog["items"]]
    entries += [(e["model"], e["stage"].lower()) for e in catalog["generic_stages"]]
    entries += [(e["seed_model"], e["display_name"]) for e in catalog["strains"]]
    entries += [(e["stages"][5]["model"], e["display_name"]) for e in catalog["strains"]]
    cols, cellw, cellh, top = 6, 220, 202, 104
    image = Image.new("RGB", (cols * cellw + 32, ((len(entries) + cols - 1) // cols) * cellh + top + 20), "#18231f")
    d = ImageDraw.Draw(image)
    d.text((24, 18), "VERDANTVICE / PIXEL COLLECTION", fill="#e3e9d5", font=font(25))
    d.text((24, 55), "25 items / 8 growth stages / 12 varieties / native 32px + 3x nearest / dark + light",
           fill="#a7b8a5", font=font(15))
    for index, (name, label) in enumerate(entries):
        x, y = 16 + (index % cols) * cellw, top + (index // cols) * cellh
        d.rectangle((x + 3, y, x + cellw - 5, y + cellh - 8), fill="#23332b")
        for offset, color in ((10, "#17201c"), (114, "#e8e5d9")):
            d.rectangle((x + offset, y + 9, x + offset + 95, y + 104), fill=color)
            large = textures[name].resize((96, 96), Image.Resampling.NEAREST)
            image.paste(large, (x + offset, y + 9), large)
        image.paste(textures[name], (x + 94, y + 113), textures[name])
        d.text((x + 10, y + 151), label, fill="#e3e9d5", font=font(13))
        d.text((x + 10, y + 171), name[:30], fill="#a7b8a5", font=font(10))
    image.save(output / "source/all-items-preview.png", optimize=True)
    image = Image.new("RGB", (8 * 160 + 190, 12 * 144 + 92), "#18231f"); d = ImageDraw.Draw(image)
    d.text((18, 16), "VERDANTVICE / GROWTH MATRIX", fill="#e3e9d5", font=font(22))
    for index, stage in enumerate(STAGES): d.text((190 + index * 160, 58), stage, fill="#b9cb7a", font=font(13))
    for row, (sid, label, _, _) in enumerate(STRAINS):
        y = 84 + row * 144
        d.text((14, y + 34), label, fill="#e3e9d5", font=font(13)); d.text((14, y + 58), sid, fill="#a7b8a5", font=font(11))
        for col, stage in enumerate(STAGES):
            x = 190 + col * 160
            d.rectangle((x, y, x + 143, y + 130), fill="#26392d" if (row + col) % 2 == 0 else "#e8e5d9")
            tile = textures[f"plant_{stage}_{sid}"].resize((128, 128), Image.Resampling.NEAREST)
            image.paste(tile, (x + 7, y), tile)
    image.save(output / "source/all-growth-stages-preview.png", optimize=True)

def build(output):
    textures, catalog = sprites(), manifest(); write_json(output / "source/catalog.json", catalog)
    plant_names = {entry["model"] for entry in catalog["generic_stages"]}
    plant_names.update(stage["model"] for strain in catalog["strains"] for stage in strain["stages"])
    carriers = defaultdict(list)
    for name, carrier, number in ITEMS: carriers[carrier].append((number, name))
    for index, stage in enumerate(STAGES): carriers["amethyst_shard"].append((12101 + index, "plant_" + stage))
    for entry in catalog["strains"]:
        carriers["wheat_seeds"].append((entry["seed_model_data"], entry["seed_model"]))
        for stage in entry["stages"]: carriers["amethyst_shard"].append((stage["custom_model_data"], stage["model"]))
    for version in ("1.21.4", "26.2"):
        root = output / version
        metadata = {"description": "VerdantVice / complete botanical pixel collection"}
        metadata.update({"pack_format": 46} if version == "1.21.4" else {"min_format": [88, 0], "max_format": [88, 0]})
        write_json(root / "pack.mcmeta", {"pack": metadata})
        for name, texture in textures.items():
            dest = root / "assets/verdantvice/textures/item" / (name + ".png"); dest.parent.mkdir(parents=True, exist_ok=True)
            texture.save(dest, optimize=True)
            model = plant_model(name) if name in plant_names else {
                "parent": "minecraft:item/generated", "textures": {"layer0": "verdantvice:item/" + name}}
            write_json(root / "assets/verdantvice/models/item" / (name + ".json"), model)
        for carrier, entries in carriers.items():
            write_json(root / "assets/minecraft/items" / (carrier + ".json"), item_definition(version, carrier, entries))
        write_json(root / "assets/verdantvice/sounds.json", {
            "plant_ready": {"sounds": [{"name": "minecraft:block.azalea_leaves.place", "type": "event"}],
                            "subtitle": "subtitles.verdantvice.plant_ready"},
            "tool": {"sounds": [{"name": "minecraft:item.brush.brushing.generic", "type": "event"}],
                     "subtitle": "subtitles.verdantvice.tool"},
            "process_complete": {"sounds": [{"name": "minecraft:block.amethyst_block.chime", "type": "event"}],
                                 "subtitle": "subtitles.verdantvice.process_complete"}})
        for translation in sorted((ROOT / "source/lang").glob("*.json")):
            values = json.loads(translation.read_text(encoding="utf-8"))
            write_json(root / "assets/verdantvice/lang" / translation.name, values)
        cover = Image.new("RGBA", (32, 32), P["ink"]); frame = ImageDraw.Draw(cover)
        frame.rectangle((1, 1, 30, 30), outline=P["gold"])
        emblem = Canvas(); fan(emblem, 16, 23, .85, "leaf", "light"); cover.alpha_composite(emblem.image)
        cover.resize((128, 128), Image.Resampling.NEAREST).save(root / "pack.png", optimize=True)
    preview(output, textures, catalog)
    print(f"Built {len(textures)} 32px textures/models in both versions; contact sheets in source/.")

if __name__ == "__main__":
    if len(sys.argv) > 2: raise SystemExit(__doc__)
    build(Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else ROOT)
