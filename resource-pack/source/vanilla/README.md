# Verified vanilla fallback references

The small item definitions in each version's `items/` directory come from
the matching, already-installed official Minecraft client JAR. These are
reference definitions, not custom art. The generator wraps each native
`model` tree with VerdantVice's numeric dispatch and uses that same complete
tree for every unowned number and range reset. It also retains native
top-level properties.

This preserves block-item model paths, clock animation, brush use animation,
bundle content rendering, and the decorated pot's special renderer. A
material name must never be assumed to mean `minecraft:item/<material>`.

`references.json` records SHA-256 values for imported definitions and the
native model files they reference, plus the three native sound-event
definitions and their indexed sound-file SHA-1 values. References were
checked against both installed client JARs and their official asset indexes.
No game binary, vanilla texture, or audio file is redistributed here.

The minimal compatibility definitions are extracts from Minecraft assets,
copyright Mojang AB / Microsoft, and are not original VerdantVice artwork.
They remain the property of their respective owners; this project does not
claim ownership of them or grant a separate license for Minecraft assets.
Minecraft's own [EULA](https://www.minecraft.net/en-us/eula) applies to use
of the game. Version numbers, asset-index IDs, native model fingerprints,
and the original sound-manifest fingerprint are retained for provenance.

Refresh only after installing both supported clients:

```powershell
python resource-pack/tools/import_vanilla.py --minecraft-directory 'C:\path\to\.minecraft'
python resource-pack/tools/build_assets.py
python resource-pack/tools/verify_assets.py
```

Custom sound aliases use `type: event` and the native event IDs. They do not
guess `.ogg` paths from an event name. Automated tests verify native fallback
trees, checked model references, all numeric dispatch gaps, and sound events.
