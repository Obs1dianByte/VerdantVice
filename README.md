# VerdantVice

A farm and trading plugin for Paper/Purpur. Players grow plants, process
harvests, trade with herbalists and manage farms with hired workers.

Growth, processing and effects use fictional game values. This is not a
guide to real-world cultivation or substance use.

## Requirements

| Server line | Server Java version | Resource pack |
|---|---:|---|
| Paper/Purpur 1.21.4 | 21 | 1.21.4 |
| Paper/Purpur 26.2 | 25 | 26.2 |

The plugin targets the Paper 1.21.4 API and Java 21 bytecode. The automated
runtime harness uses Paper 26.2; the compatibility target is not a claim
that every server build or third-party plugin combination has been tested.
Folia, Spigot and CraftBukkit are not supported.

## Install

1. Build the project or download the artifacts from a successful
   [GitHub Actions run](https://github.com/Obs1dianByte/VerdantVice/actions).
2. Stop the server and put `VerdantVice-1.0.0.jar` in `plugins/`.
   Do not install the `-thin.jar` file.
3. Start the server and wait for `Plugin enabled successfully.`.
4. Review the files generated in `plugins/VerdantVice/`, then open `/vv`.
   `/vv guide` provides the in-game introduction.

SQLite and physical emeralds work without additional plugins. See
[installation](INSTALLATION.md) for database setup and updates.

## Start a farm

Ordinary players have the default gameplay permissions; no operator status
or administrator-issued items are required.

1. Use `/vv guide`, collect emeralds and find a farmer. Each farmer has one
   40% chance to become a herbalist when evaluated.
2. At a novice herbalist, buy a seed, planting pot, watering can and drying
   rack. All care tools are available at this tier. Place the pot, plant the
   seed, care for the plant, harvest and dry the batch before selling it.
3. Buy a selection wand and manager table from the novice herbalist. Select
   both corners, then place the table to create the farm. Alternatively,
   `/vv farm create My Farm` creates the selected farm and its table directly.
4. Purchases and sales develop the herbalist. Apprentice unlocks curing;
   journeyman unlocks grinding, rolling paper and filters. A Verdant Expert
   sells the hiring contract for workers.

The basic farm setup (Blueberry seed, pot, can, rack, wand and table) costs
62 emeralds at default prices. Farming can begin before creating a named
farm. See the [strain catalog](STRAINS.md) for seed names and saved IDs.

The processing chain is harvest → drying → optional curing → grinding →
optional mixing → rolling. Dry batches can also be sold directly. Use
`/vv recipes` for the recipe guide and `/vv farm` for the farm dashboard.
Farms include membership roles, linked storage and worker schedules.

Players can choose `en_US`, `pl_PL`, `de_DE`, `es_ES`, `cs_CZ` or `uk_UA`
with `/vv language <code>`.

## Resource pack

The optional pack includes item icons, seed variants and plant growth
models. Use the ZIP matching the client's Minecraft version. Without the
pack, the plugin uses vanilla materials.

[Item preview](resource-pack/source/all-items-preview.png) ·
[Growth-stage preview](resource-pack/source/all-growth-stages-preview.png)

Build outputs:

```text
verdantvice-paper/build/resource-packs/VerdantVice-resourcepack-1.21.4.zip
verdantvice-paper/build/resource-packs/VerdantVice-resourcepack-26.2.zip
```

Host the selected ZIP over HTTPS and configure its URL and SHA-1 in
`resourcepack.yml`. [Resource-pack documentation](RESOURCE_PACK.md) covers
hosting, model IDs and editing the pixel-art sources.

## Build and test

Use JDK 21 or newer and the included Gradle wrapper:

```powershell
.\gradlew.bat clean build
```

On Linux/macOS, use `./gradlew clean build`. The distributable is
`verdantvice-paper/build/libs/VerdantVice-1.0.0.jar`. The build runs the
JUnit suites and checks the packaged JAR and both resource-pack archives.

Additional checks are separate:

```powershell
# MySQL and MariaDB; requires Docker
.\gradlew.bat :verdantvice-paper:networkDatabaseTest

# Build plus isolated Paper startup/restart checks; requires JDK 25
pwsh -File scripts/Verify.ps1
```

The Paper test requires a cached server and an already accepted EULA. It
does not use your normal world or database. See [testing](TESTING.md) for
prerequisites, report locations, client checks and coverage boundaries.

## Server operations

Back up the database, `data/signing.key` and `data/processing-stations.yml`
together. Losing the signing key invalidates existing signed items. SQLite
backups are built in; MySQL/MariaDB backups are the administrator's job.

Built-in duplicate-item policing is disabled by default
(`security.anti-dupe-enabled: false`). Signatures, ownership and transaction
recovery remain active. Other plugins may not prevent copies of these items;
see [configuration](CONFIGURATION.md#security-and-modules) before enabling it.

Use `/vv admin database` for connection and queue status,
`/vv admin performance` for runtime counters, and `/vv admin debug` for a
diagnostic report. Never attach credentials, signing keys, database dumps
or player data to a public issue.

## Integrations and limits

Vault with an economy provider replaces the emerald economy.
PlaceholderAPI exposes `%verdantvice_*%` placeholders. WorldGuard,
GriefPrevention and Lands supply region permission checks.

ItemsAdder, Oraxen and LuckPerms are declared as soft dependencies, but have
no dedicated runtime adapters. Built-in visuals use `CustomModelData`;
permissions use Bukkit nodes. Some module/integration settings are accepted
without disabling or replacing runtime systems; see
[configuration](CONFIGURATION.md) and [permissions](PERMISSIONS.md).

The API becomes available after asynchronous database startup. Plugins
using it must handle temporary service absence during startup.

## Reference

[Commands](COMMANDS.md) · [Permissions](PERMISSIONS.md) ·
[Configuration](CONFIGURATION.md) · [Upgrades](MIGRATION.md) ·
[Troubleshooting](TROUBLESHOOTING.md) · [API](API.md) ·
[Building](BUILDING.md) · [Development](DEVELOPMENT.md) ·
[Contributing](CONTRIBUTING.md)

Report reproducible problems through the
[issue tracker](https://github.com/Obs1dianByte/VerdantVice/issues).
