# VerdantVice resource pack

This folder contains separate technical variants for Minecraft 1.21.4 and
26.2 because their pack metadata versions differ (46 versus 88.0). Both packs
use the same hand-tuned pixel-art texture set and custom-model-data numbers
declared in `items.yml`.

The plugin does not require either pack. Without it, all items, stations, and
plant displays use vanilla materials.

The complete collection covers all 25 item types, 12 seed packet labels,
eight generic growth stages and 96 variety-stage combinations: 141 texture
and model files per technical variant. Plants use double-sided cross planes
instead of flat inventory cards. The 12 named varieties use stylised game
colours, not photographs or claims about actual botanical phenotypes.

Rebuild from the editable 32px source (Python 3 + Pillow):

```text
python tools/build_assets.py
python tools/verify_assets.py
```

Zip the *contents* of the desired version folder, host the ZIP over HTTPS, put
its URL and SHA-1 in `resourcepack.yml`, then run `/vv admin reload`.

Edit `source/pixel_art.py`, not generated files. There is no background
cutout, blur or noise. `source/catalog.json` contains every item, variety
and stage mapping. `source/all-items-preview.png` shows dark/light native
and enlarged previews; `source/all-growth-stages-preview.png` shows all 96
variety-stage combinations. The previous atlas remains as provenance only.
The generated plant reference and exact prompt are documented in
`source/art-direction.md`. The normal Java test/check task also validates
the pack against the actual runtime selectors and all configured items.

Vanilla fallbacks use complete item definitions imported from each matching
official client, not guessed `minecraft:item/<material>` paths. This keeps
block models and dynamic clock, brush, bundle, and decorated-pot behavior
intact. Custom art stays identical across variants; the native item
definitions may differ between Minecraft releases. Sound aliases reference
verified vanilla events with `type: event`, not invented audio-file paths.
Provenance, refresh commands, and the vanilla copyright notice are in
`source/vanilla/README.md`.
