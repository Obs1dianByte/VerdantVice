# VerdantVice commands

The main command is `/verdantvice`, with alias `/vv`. Bukkit requires
`verdantvice.use` to use it. With no arguments, it opens the main GUI for
the player.

This document describes the behavior of the actual command executor. Some
`usage` messages in language files include broader, future argument
variants; the current parser does not support them automatically.

## Player commands

| Command | Action | Console |
|---|---|---|
| `/vv` | Opens the main GUI | No |
| `/vv help` | Shows available commands; a page argument is not used | Yes |
| `/vv guide` | Issues, registers and opens the basic guide book | No |
| `/vv profile` | Opens the current player's skills view | No |
| `/vv skills` | Opens the skills view | No |
| `/vv strains` | Opens the strain catalog | No |
| `/vv recipes` | Opens a localized guide to all processing recipes | No |
| `/vv recipes roll` | Starts the same minigame as `/vv roll` | No |
| `/vv roll` | Checks signed paper, a filter and Ground Product/Mixture, then starts the minigame | No |
| `/vv market` | Opens the market view | No |
| `/vv contracts` | Opens the contracts view | No |
| `/vv farm` | Opens farm management | No |
| `/vv workers` | Opens worker management | No |
| `/vv language` | Shows a localized, clickable list of supported languages | No |
| `/vv language <code>` | Saves the player's language | No |
| `/vv notifications` | Opens notification settings | No |
| `/vv notifications <type> <on\|off>` | Toggles a notification type | No |
| `/vv resourcepack` | Resends the configured resource pack | No |

Supported language codes:

```text
pl_PL, en_US, de_DE, es_ES, cs_CZ, uk_UA
```

Notification types can use lowercase letters and hyphens, for example
`plant-ready`, `drying-complete` and `market-change`.

## Genetics

`/vv genetics` opens the laboratory and the player's custom lines. To create
a new line, hold two different signed seeds in the main hand and off hand,
then use `/vv genetics <name>`. Both seeds are marked as consumed, and a
successful cross gives the player one signed seed of the new line. The name
must contain 3–24 allowed characters and must not contain words prohibited
by `genetics.yml`.

## Farms

| Command | Action |
|---|---|
| `/vv farm create <name>` | Creates a farm from the current selection or the default region |
| `/vv farm delete` | Immediately deletes the farm owned by the sender |
| `/vv farm invite <online-player>` | Adds an online player with the `MEMBER` role |
| `/vv farm remove <player>` | Removes a member; also works for an offline player |
| `/vv farm storage <type>` | Assigns the targeted container as farm storage |
| `/vv farm storage unlink` | Unlinks the targeted storage without destroying the block or its contents |

### Farm dashboard

`/vv farm` opens the dashboard of the currently accessible farm. It shows a
fresh snapshot of plants, workers, storages, drying racks, curing containers,
salaries, permissions and statistics that are actually available. Submenus
retain `farmId` while paging, refreshing and navigating back. Storage can be
safely unlinked from its tile after confirmation; its block and contents are
not destroyed.

Viewing data requires the domain permission `VIEW`. Storage actions require
`MANAGE_STORAGE`, worker priorities require `MANAGE_WORKERS`, and the
permissions list requires `MANAGE_MEMBERS`. Refresh reads the current state
from manager caches; the GUI does not execute SQL queries on the main thread.

A new farm name must contain 3–48 characters. Despite the broader language
file text, the executor accepts no extra argument or interactive confirmation
for `delete`. Check that the correct farm is selected before using it.

Select a region with a signed `selection-wand`: left click sets the first
corner and right click sets the second. Without a selection, a region is
created around the player.

Assign storage by looking directly at a `CHEST`, `TRAPPED_CHEST`, `BARREL`
or any `SHULKER_BOX` from within 6 blocks. The entire container must be
inside the region. Both halves of a double chest are treated as one physical
storage with a single record. Argument types:

```text
seed-storage, tool-storage, fresh-harvest-storage, dry-product-storage,
packaged-product-storage, salary-storage, water-storage, general-storage
```

A farm can have several ordinary storages, but exactly one active
`salary-storage`. Reassigning an existing link does not modify the database.
`unlink` removes only the association and allows the same container to be
assigned again later.

## Administrative commands

Every command below first requires `verdantvice.admin`.

| Command | Action | Additional requirement |
|---|---|---|
| `/vv admin reload` | Safely reloads configuration and languages, excluding the database | `verdantvice.admin.reload` |
| `/vv admin save` | Saves all loaded profiles | — |
| `/vv admin give <player> <type> [strain\|amount] [amount]` | Issues and registers signed items; amount 1–64 | `verdantvice.admin.give` |
| `/vv admin seed <player> <strain>` | Shortcut issuing one signed seed | `verdantvice.admin.give` |
| `/vv admin plant <strain>` | Plants at the targeted station within 6 blocks | Player |
| `/vv admin setstage <stage>` | Changes the targeted plant's stage | Player |
| `/vv admin setquality <grade>` | Replaces the held batch with a version of the requested quality | Player |
| `/vv admin spawnherbalist [tier] [--force]` | Converts a villager targeted within 8 blocks or spawns a farmer; `--force` bypasses only the village limit | Player; force requires `verdantvice.admin.spawnherbalist.force` |
| `/vv admin hireworker` | Starts normal hiring for a villager targeted within 8 blocks | Player |
| `/vv admin removeplant` | Safely removes the targeted plant | Player |
| `/vv admin inspect` | Shows the targeted plant's exact state | Player |
| `/vv admin market` | Opens the market GUI | Player |
| `/vv admin contract` | Opens the contracts GUI | Player |
| `/vv admin resetplayer <player> confirm` | Resets the player's profile | — |
| `/vv admin migrate` | Returns the current database manager's initialization/migration result | — |
| `/vv admin database` | Shows the database type, state, active operations and queue | — |
| `/vv admin debug` | Saves an anonymized report in `logs/` | — |
| `/vv admin performance` | Shows plant and farm counts and the database queue | — |

`/vv admin migrate` does not replace a restart after installing a newer JAR.
The database manager retains its startup result, and actual pending
migrations run before the plugin becomes ready.

`spawnherbalist` accepts:

```text
NOVICE, APPRENTICE, JOURNEYMAN, EXPERT, MASTER, VERDANT_EXPERT
```

If the target is a villager, the command preserves an existing herbalist and
its UUID, reputation, experience and offers. An ordinary villager undergoes
a controlled conversion; a worker cannot be converted. With no entity target,
a new farmer is spawned above the targeted block or near the player. Without
`--force`, `herbalist.max-per-village` applies. Explicit `--force` bypasses
only that limit and the random conversion check; entity, world, uniqueness
and database checks still apply. Supplying a tier raises experience to at
least the `levels.<TIER>` threshold in `villagers.yml`, without resetting
higher values. A save failure removes only a newly spawned entity.

`hireworker` does not bypass hiring rules merely because it is an admin
command. The target must be a registered `VERDANT_EXPERT` herbalist, the player
must hold a correctly signed `PLANTATION_CONTRACT` in the main hand, and the
manager must find that player's farm with available capacity. The contract
is consumed atomically only when the operation succeeds.

## Processing stations

Only right click opens the drying-rack, curing-container or grinder GUI.
Left click starts a normal break attempt. An idle station is reserved while
its signed item is issued; its block is then removed without a vanilla drop,
`processing-stations.yml` is updated, and the player receives exactly one
signed item. With a full inventory, the item drops beside the block. An
active process or a second concurrent attempt returns `errors.busy`.
Shift + right click on a grinder still starts mixing.

## Identifiers for `admin give`

The parser ignores case and converts hyphens to underscores. Available types:

```text
seed
planting-pot
watering-can
plant-scanner
cleaning-kit
treatment-spray
nutrient-pack
repair-kit
drying-rack
curing-container
grinder
farm-manager-table
selection-wand
rolling-paper
filter
fresh-batch
dry-batch
cured-batch
ground-product
mixture
package
joint
plantation-contract
guide-book
advanced-book
```

`seed` requires a valid strain identifier:

```text
/vv admin give Alex seed berry_fog 8
/vv admin seed Alex berry_fog
```

Issuing stateful products (`fresh-batch`, `joint`, etc.) through the command
alone does not create their complete corresponding production history. For
integrity tests, prefer items produced through the normal workflow.

## Stages and quality grades

Plant stages:

```text
SEED, SPROUT, SEEDLING, VEGETATIVE, MATURING, READY, OVERRIPE, SPOILED
```

Quality grades:

```text
SPOILED, VERY_POOR, STREET, COMMON, GOOD, PREMIUM,
TOP_SHELF, PERFECT_BATCH, VERDANT_GRADE
```

## Completion and errors

Tab completion suggests main commands, farm operations, languages,
notification types, admin commands, online players, stages, grades, item
types and strains.

During asynchronous startup, commands respond with a `not ready` message.
Database or operation failures show a safe, localized message, while details
go to the server log. Administrative actions are logged with the sender's
name.
