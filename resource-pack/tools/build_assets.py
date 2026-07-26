"""Build both supported resource-pack variants from the generated sprite atlas."""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

from PIL import Image


SPRITES = [
    "seed",
    "planting_pot",
    "plant_sprout",
    "plant_seedling",
    "plant_vegetative",
    "plant_ready",
    "plant_overripe",
    "plant_spoiled",
    "drying_rack",
    "grinder",
    "curing_container",
    "package",
    "joint",
    "farm_manager_table",
    "watering_can",
    "plant_scanner",
]

ITEMS = {
    "wheat_seeds": [(12001, "seed")],
    "decorated_pot": [(12002, "planting_pot")],
    "glass_bottle": [(12003, "watering_can")],
    "clock": [(12004, "plant_scanner")],
    "brush": [(12005, "package")],
    "honey_bottle": [(12006, "curing_container")],
    "bone_meal": [(12007, "seed")],
    "iron_nugget": [(12008, "grinder")],
    "barrel": [(12009, "drying_rack")],
    "chiseled_bookshelf": [(12010, "curing_container")],
    "stonecutter": [(12011, "grinder")],
    "lectern": [(12012, "farm_manager_table")],
    "blaze_rod": [(12013, "plant_scanner")],
    "paper": [(12014, "package")],
    "stick": [(12015, "seed"), (12017, "joint")],
    "bundle": [(12016, "package")],
    "amethyst_shard": [
        (12101, "seed"),
        (12102, "plant_sprout"),
        (12103, "plant_seedling"),
        (12104, "plant_vegetative"),
        (12105, "plant_ready"),
        (12106, "plant_ready"),
        (12107, "plant_overripe"),
        (12108, "plant_spoiled"),
    ],
}


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def transparent_crop(cell: Image.Image) -> Image.Image:
    image = cell.convert("RGBA")
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            red, green, blue, alpha = pixels[x, y]
            luminance = max(red, green, blue)
            if luminance <= 23:
                new_alpha = 0
            elif luminance < 48:
                new_alpha = int((luminance - 23) / 25 * alpha)
            else:
                new_alpha = alpha
            pixels[x, y] = (red, green, blue, new_alpha)
    bbox = image.getbbox()
    if bbox is None:
        raise ValueError("sprite cell became empty")
    subject = image.crop(bbox)
    canvas = Image.new("RGBA", (320, 320), (0, 0, 0, 0))
    subject.thumbnail((288, 288), Image.Resampling.LANCZOS)
    offset = ((320 - subject.width) // 2, (320 - subject.height) // 2)
    canvas.alpha_composite(subject, offset)
    return canvas.resize((32, 32), Image.Resampling.LANCZOS)


def item_definition(carrier: str, entries: list[tuple[int, str]]) -> dict:
    return {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "index": 0,
            "entries": [
                {
                    "threshold": threshold,
                    "model": {
                        "type": "minecraft:model",
                        "model": f"verdantvice:item/{model}",
                    },
                }
                for threshold, model in entries
            ],
            "fallback": {
                "type": "minecraft:model",
                "model": f"minecraft:item/{carrier}",
            },
        }
    }


def build_variant(root: Path, sprites: dict[str, Image.Image], modern: bool) -> None:
    root.mkdir(parents=True, exist_ok=True)
    pack = {
        "pack": {
            "description": "VerdantVice optional fictional botanical models"
        }
    }
    if modern:
        pack["pack"]["min_format"] = [88, 0]
        pack["pack"]["max_format"] = [88, 0]
    else:
        pack["pack"]["pack_format"] = 46
    write_json(root / "pack.mcmeta", pack)

    texture_root = root / "assets" / "verdantvice" / "textures" / "item"
    model_root = root / "assets" / "verdantvice" / "models" / "item"
    texture_root.mkdir(parents=True, exist_ok=True)
    for name, sprite in sprites.items():
        sprite.save(texture_root / f"{name}.png", optimize=True)
        write_json(
            model_root / f"{name}.json",
            {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": f"verdantvice:item/{name}"},
            },
        )

    for carrier, entries in ITEMS.items():
        write_json(
            root / "assets" / "minecraft" / "items" / f"{carrier}.json",
            item_definition(carrier, entries),
        )

    write_json(
        root / "assets" / "verdantvice" / "sounds.json",
        {
            "plant_ready": {
                "sounds": ["minecraft:block/azalea_leaves/place1"],
                "subtitle": "subtitles.verdantvice.plant_ready",
            },
            "tool": {
                "sounds": ["minecraft:item/brush/brushing/generic1"],
                "subtitle": "subtitles.verdantvice.tool",
            },
            "process_complete": {
                "sounds": ["minecraft:block/amethyst_block/chime"],
                "subtitle": "subtitles.verdantvice.process_complete",
            },
        },
    )
    write_json(
        root / "assets" / "verdantvice" / "lang" / "en_us.json",
        {
            "subtitles.verdantvice.plant_ready": "A fictional plant is ready",
            "subtitles.verdantvice.tool": "A care tool is used",
            "subtitles.verdantvice.process_complete": "Processing completes",
        },
    )
    sprites["seed"].resize((128, 128), Image.Resampling.NEAREST).save(
        root / "pack.png",
        optimize=True,
    )


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: build_assets.py <atlas.png> <resource-pack-root>")
    atlas_path = Path(sys.argv[1]).resolve()
    output = Path(sys.argv[2]).resolve()
    source = output / "source"
    source.mkdir(parents=True, exist_ok=True)
    shutil.copy2(atlas_path, source / "verdantvice-atlas.png")

    atlas = Image.open(atlas_path).convert("RGB")
    x_edges = [round(index * atlas.width / 4) for index in range(5)]
    y_edges = [round(index * atlas.height / 4) for index in range(5)]
    sprites: dict[str, Image.Image] = {}
    for index, name in enumerate(SPRITES):
        row, column = divmod(index, 4)
        cell = atlas.crop(
            (
                x_edges[column],
                y_edges[row],
                x_edges[column + 1],
                y_edges[row + 1],
            )
        )
        sprites[name] = transparent_crop(cell)

    build_variant(output / "1.21.4", sprites, modern=False)
    build_variant(output / "26.2", sprites, modern=True)


if __name__ == "__main__":
    main()
