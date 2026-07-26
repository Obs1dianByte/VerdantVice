# VerdantVice resource pack

This folder contains separate technical variants for Minecraft 1.21.4 and
26.2 because their pack metadata versions differ (46 versus 88.0). Both packs
use the same fictional pixel-art texture set and custom-model-data numbers
declared in `items.yml`.

The plugin does not require either pack. Without it, all items, stations, and
plant displays use vanilla materials.

To rebuild the generated textures after replacing the atlas:

```text
python tools/build_assets.py source/verdantvice-atlas.png .
```

Zip the *contents* of the desired version folder, host the ZIP over HTTPS, put
its URL and SHA-1 in `resourcepack.yml`, then run `/vv admin reload`.

The supplied art is deliberately simple. Replace any PNG in
`assets/verdantvice/textures/item/` while retaining its filename and square
dimensions. The source atlas was AI-generated specifically for this project,
then mechanically split and reduced to 32×32 sprites.
