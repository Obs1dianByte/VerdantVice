# Troubleshooting

## Collect safe diagnostics first

Run:

```text
/vv admin database
/vv admin performance
/vv admin debug
```

The last command creates:

```text
plugins/VerdantVice/logs/diagnostic-<UTC>.yml
```

The report includes plugin, server, and Java versions; database state and
type; queue size; farm/plant/player counts; language; debug status; and
declared modules. It does not read the database password or `signing.key`.

Include the console output starting at the first error in a bug report.
Do not publish `database.yml`, the key, a database dump, or player data.

## The plugin does not start

### `UnsupportedClassVersionError` or incorrect Java

- Paper/Purpur 1.21.4: use at least Java 21.
- Paper/Purpur 26.2: use Java 25.
- Ensure the server process, not just the terminal, uses the correct `java`.

Check `java -version` in the same startup script.

### `-thin.jar` was installed

`VerdantVice-1.0.0-thin.jar` does not include required libraries. Replace it
with `VerdantVice-1.0.0.jar` from the `shadowJar`/`build` task and remove
any duplicate plugin files.

### `Invalid configuration`

Check the most recently edited YAML file, indentation, and value types.
Common constraints:

- `plants-per-cycle`: 1–10000.
- `max-plants-per-chunk`: 1–512.
- `database-save-seconds`: 5–86400.
- Language must be one of the six supported codes.
- Database type must be `SQLITE`, `MYSQL`, or `MARIADB`.

If safe reload rejects a configuration, the active snapshot remains
unchanged. Fix the file and retry the command.

### Missing or incomplete language

`en_US.yml` is required, and other languages must contain all its text keys.
Restore missing keys from the resources in the same JAR; do not remove the
entire directory without preserving your custom translations.

## Commands report that the plugin is not ready

Database startup and data loading are asynchronous. Wait for:

```text
Plugin enabled successfully.
```

If this message never appears, an earlier exception identifies the module
that failed to start. Check the database connection, migrations, and file
access.

## Database problems

### SQLite: `database is locked` / timeout

- Do not open the live database with a tool that writes to it.
- Ensure only one server instance uses the file.
- Do not copy only the `.db` file while the server is running.
- Preserve `-wal` and `-shm` during diagnosis, but restore from a consistent
  backup file.
- Consider increasing `sqlite.busy-timeout-millis`, then restart.

### MySQL/MariaDB: connection failure

Check the host, port, database name, account, password, firewall, TLS, and
permissions to create and alter tables. `database.yml` cannot be reloaded
at runtime: perform a full restart.

`retry.attempts` applies to transient operations; it will not repair
incorrect credentials.

### The database queue keeps growing

Check `/vv admin database`. For network databases, cautiously adjust
`pool.maximum-size`, `executor.threads`, and `executor.queue-capacity`.
SQLite deliberately uses one worker, so reduce the load first:

- `plants-per-cycle`.
- Plant and worker counts.
- Save and update frequency.

Do not increase SQLite executors: the code enforces a single worker anyway.

### Checksum mismatch, a version gap, or `success = 0`

Do not edit `vv_schema_history`. Restore a consistent backup or install a
JAR compatible with the database version. See [MIGRATION.md](MIGRATION.md).

## An item is rejected as invalid or duplicated

VerdantVice checks both the PDC signature and the instance state in
`item_registry`. An item is rejected after modification, copying, reuse, or
restoring the database to a different point in time from player inventories.

Check:

1. Whether `data/signing.key` has changed.
2. Whether the database and key come from the same backup.
3. Whether another plugin modifies PDC/`CustomModelData`.
4. Whether the player is attempting to use an input already consumed by a
   transaction.

Do not regenerate the key as a fix. Doing so invalidates all remaining
items.

## A plant is not growing

Check `/vv admin inspect`:

- Stage, health, hydration, nutrition, and stress.
- Minimum health required for growth.
- The `GROWTH_LOCK` problem.
- Light and cleanliness values.
- `performance.offline-growth` and `unloaded-chunk-growth`.
- Whether the scheduler reports an error in the log.

Updates are batched, so changes may not appear immediately when many plants
are present.

## Planting or farm creation is blocked

- Seeds, pots, and the selection wand must be signed.
- Stations must meet minimum spacing requirements.
- Player and chunk limits apply.
- The action must pass farm-role permissions.
- Active WorldGuard/GriefPrevention/Lands integrations must permit it.
- If an adapter fails, the default `DENY_IF_UNKNOWN` blocks the action.

Check the adapter warning in the log. Change the fallback away from
`DENY_IF_UNKNOWN` only after deliberately assessing the risk.

## A processing station is occupied

A station with an active batch cannot be broken. Wait for completion and
collect the output as its owner. The job state is in the database and the
station location is in `data/processing-stations.yml`; both must come from
the same backup.

After a failure, the minigame should return inputs through its controlled
recovery path. If an operation has an ambiguous database commit, do not
manually issue a duplicate before checking the registry and log.

## A worker is standing still or not performing a task

Check:

- The world-time schedule in `workers.yml`.
- The path, doors, elevation, and whether the chunk is loaded.
- `path-retry-count` and `path-timeout-seconds`.
- Target reservations and the plant limit per worker.
- Morale, unpaid salaries, a bed, food, and workload.
- `MANAGE_WORKERS` permissions and the farm's worker limit.

Emergency teleportation is disabled by default. Enabling
`emergency-worker-teleport` should be a final safeguard, not a substitute
for correct navigation.

## A herbalist does not appear

Only a villager with the `FARMER` profession undergoes a one-time evaluation.
The `conversion-chance`, village-radius limit, and saved evaluation marker
apply. Changing the chance does not re-evaluate villagers already checked.
Use the administrative command for a controlled test.

`/vv admin spawnherbalist [tier]` still respects the village limit. If you
are not looking at a villager, the command creates a new farmer above the
targeted block.

## A worker cannot be hired

`verdantvice.admin` alone does not bypass domain rules. `/vv admin hireworker`
requires all of the following:

- A registered `VERDANT_EXPERT` herbalist targeted within 8 blocks.
- A correctly signed `PLANTATION_CONTRACT` in the main hand.
- The player's farm, with permission to manage it.
- Available capacity below the worker limit.
- A herbalist that is not already hired.

Do not issue a second contract after an ambiguous database error without
checking the item registry.

## Vault does not work

Vault itself is not an economy provider. Install a provider as well, set
`provider: VAULT` or `vault.enabled: true`, run `/vv admin reload`, and check
the log for `Economy: Vault.`. If no service is available, VerdantVice safely
continues using emeralds.

## PlaceholderAPI returns an empty value

PlaceholderAPI must be active when VerdantVice completes startup. The
identifier is `verdantvice`. Available placeholders:

```text
%verdantvice_farm_name%
%verdantvice_farm_level%
%verdantvice_plants%
%verdantvice_ready_plants%
%verdantvice_workers%
%verdantvice_reputation%
%verdantvice_tolerance%
%verdantvice_market_<strain_id>%
```

An unknown parameter returns `null`; a missing player returns empty text.

## The resource pack does not work

- Select the correct 1.21.4 or 26.2 variant.
- `pack.mcmeta` must be at the ZIP root.
- The URL must lead directly to the ZIP over HTTPS.
- SHA-1 must contain exactly 40 hexadecimal characters and match the current
  file.
- After changing the file, clear the client cache or change the URL.
- Check `enabled` and `send-on-join`.

Without the pack, items should appear as valid vanilla materials. Complete
instructions: [RESOURCE_PACK.md](RESOURCE_PACK.md).

## The API is `null`

This may be normal during a dependent plugin's `onEnable()` because
VerdantVice registers the service only after loading the database and cache.
Resolve it lazily through `ServicesManager` and retry after the readiness
message. Do not cast the plugin object. See [API.md](API.md).

## `runServer` does not start

- Use JDK 25.
- Ensure access to Gradle and Paper repositories.
- Read the EULA and, if required, set `eula=true` in `run/eula.txt`.
- Remove only corrupt files from the test `run/` environment, not a production
  directory.
- Do not use this task to run 1.21.4: it is configured for 26.2.
