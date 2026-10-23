# VerdantVice pixel collection — revision 2

Final source: `pixel_art.py`. Final resolution: 32×32 RGBA, integer-coordinate
pixel clusters, binary transparency (0/255), small controlled per-icon palette.
The source has no random noise, filtering, antialiasing or luminance-based
background removal. Dark outline/detail pixels are deliberately retained.

Art direction: vanilla-adjacent readable inventory silhouettes, forest/sage
greens, terracotta, warm cream paper, muted copper and cool silver. Each of the
25 item types has its own illustration. Seed packets use a colour accent and
a small distinct barcode, so labels are not distinguished by colour alone.
Plant progression uses eight distinct silhouettes. Variety accents identify
gameplay variants; they are not photographs or botanical phenotype claims.

The original `verdantvice-atlas.png` is retained as historical provenance only.
It is not consumed by the new builder.

## Generated reference

`plant-concept.png` was generated with the built-in imagegen tool with true
transparency, then visually inspected. It serves as a botanical silhouette
and cluster reference, not as a giant raster automatically cut into the pack.
The final low-resolution assets are deliberately redrawn as editable pixel
geometry so every icon remains sharp and regenerates exactly.

Prompt used verbatim:

> Use case: stylized-concept. Asset type: a single Minecraft inventory pixel-art botanical icon, production game texture. Primary request: one mature green hemp/cannabis plant with recognizable narrow serrated 5-to-7-point fan leaves and a compact flowering top, rooted in a small dark soil mound, no pot. True low-resolution pixel art designed on a 32 by 32 pixel logical canvas, enlarged by nearest-neighbor only; extremely crisp square pixel clusters, NO antialiasing, NO rendered 3D, NO gradients, NO cinematic glow, NO noise. Restrained Minecraft vanilla-style palette of 9 opaque colors: deep forest outline, three sage/emerald greens, yellow-green highlights, two warm brown soil colors, muted amber bud accents. The silhouette must read instantly at 32 pixels. Centered full plant with 2 logical pixels transparent padding. Genuinely transparent background, not a black backdrop and not a checkerboard drawing. Only one standalone icon, not a sheet, no text, border, watermark, floating sparkles or decorative extra objects.

## Visual QA

`all-items-preview.png`: all 25 items, eight generic growth stages, every seed
packet and every mature variety, shown on both dark and light backgrounds,
at native 32px and integer 3× nearest-neighbour enlargement.

`all-growth-stages-preview.png`: all 96 variety × stage combinations, with
alternating light/dark cells. Early developmental silhouettes are shared;
variety accents appear in flowers. These are stylised game graphics, not
images of a real harvest or a verified authentic variety.

World plants use two double-sided planes rotated −45°/+45°. All other items
use Minecraft generated-item models. `PlantDisplayService` uses the exact
integer mappings in `catalog.json` via `ResourcePackModels`.
