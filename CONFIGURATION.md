# Configuring VerdantVice

## Location and format

All files are copied to `plugins/VerdantVice/` on the first startup. The
plugin loads 17 YAML files and six language files from
`plugins/VerdantVice/languages/`. Source files are in
`verdantvice-paper/src/main/resources/`.

Use UTF-8 and spaces instead of tabs, and preserve list and map indentation.
Time values use the unit in their key name (`-ticks`, `-seconds`, `-minutes`,
`-hours`, `-millis`).

## Reloading

```text
/vv admin reload
```

The command atomically loads and validates all safe files, reloads languages
and the strain catalog, and redetects region protection and the economy.
`database.yml` is deliberately skipped and reported as requiring a restart.

Always perform a full restart after changing:

- the database type, address, credentials, pool or database queue;
- the plugin JAR;
- the signing key, which should not normally be changed;
- settings whose effects initialize a scheduler, if a safe reload does not
  apply the change.

Never use the server's `/reload` command.

## Startup validation

The plugin rejects configuration when:

- `performance.plants-per-cycle` is outside `1..10000`;
- `performance.max-plants-per-chunk` is outside `1..512`;
- `performance.database-save-seconds` is outside `5..86400`;
- the default language is not one of the six supported languages;
- the database type is not `SQLITE`, `MYSQL` or `MARIADB`;
- the SQLite or backup path escapes the VerdantVice directory;
- database pool, retry or queue settings are outside the code's allowed ranges;
- a YAML string contains a NUL character.

On validation failure, the plugin does not become ready.

## File map

| File | Responsibility |
|---|---|
| `config.yml` | Language, limits, performance, security and module declarations |
| `database.yml` | SQLite/MySQL/MariaDB, pooling, retries and backups |
| `plants.yml` | State-change rates, care, problems and stage materials |
| `strains.yml` | 12 real strain names and gameplay parameters; see the [catalog](STRAINS.md) |
| `genetics.yml` | Genetics thresholds and settings catalog |
| `quality.yml` | Quality weights, drying, curing, grinding and mixing |
| `effects.yml` | Effect duration, strength, tolerance, risk and safe limits |
| `villagers.yml` | Herbalist conversion, tiers, offers and hiring contracts |
| `workers.yml` | AI, schedules, tasks, salaries and morale |
| `economy.yml` | Emeralds/Vault and price limits |
| `market.yml` | Updates, supply, demand, history and events |
| `contracts.yml` | Contract generation, deadlines, rewards and limits |
| `gui.yml` | Sizes, slots, navigation and minigame parameters |
| `items.yml` | Vanilla materials and `CustomModelData` |
| `integrations.yml` | Optional integrations and protection-failure policy |
| `resourcepack.yml` | Delivery of a hosted resource pack |
| `notifications.yml` | Notification types, channels and sounds |

## `config.yml`

### Language

```yaml
default-language: en_US
auto-detect-client-language: true
debug: false
```

With automatic detection enabled, a supported client language is used.
Manual selection through `/vv language` takes precedence and is saved in
the database. The console uses the default language.

### Limits

- `limits.plants-per-player` — default 64;
- `limits.farms-per-player` — default 2;
- `limits.minimum-station-distance` — minimum station spacing, default 2.0.

An administrator with `verdantvice.admin` bypasses plant and farm limits,
but not every other integrity rule.

### Performance

- `plant-update-seconds` and `plants-per-cycle` control growth batching;
- `worker-update-ticks` controls the worker state-machine interval;
- `database-save-seconds` sets the batched profile-save interval;
- `offline-growth` and `unloaded-chunk-growth` allow elapsed-time calculation
  without an active player or loaded chunk;
- `max-plants-per-chunk`, `max-workers-per-farm` and `max-displays-per-chunk`
  are hard protective limits;
- `emergency-worker-teleport` and `emergency-worker-teleport-seconds` control
  optional emergency recovery of a stuck worker.

### Security and modules

`security.anti-dupe-enabled` defaults to `false`, including when an existing
configuration has no such key. `/vv reload` applies changes to this switch.
When disabled, VerdantVice does not scan or cancel inventory actions for
duplicate identities, and a present, authentic copy is not rejected solely
because its identity was already consumed or is missing from the registry.
Other plugins and Minecraft itself do not necessarily protect these items.

Items still require their original signature and owner. Stateful items are
single units, not stacks. Batch stages, station locks, pending deliveries,
rolling refunds and transaction receipts remain enforced: disabling duplicate
policing does not disable gameplay rules or crash recovery. Existing registry
data is retained. Set the switch to `true` to restore strict duplicate checks.
`security.require-signed-items` is retained for configuration compatibility;
it does not select an unsigned mode. `reservation-timeout-seconds` is read at
startup; `interaction-cooldown-millis` is a catalog setting.

The `modules.*` section is reported in diagnostics. The current bootstrap
creates every subsystem regardless of these flags; use the dedicated
`enabled` keys to functionally disable effects or contracts.

## `database.yml`

Default configuration:

```yaml
type: SQLITE
sqlite:
  file: data/verdantvice.db
  backup-on-start: true
  backup-interval-hours: 12
```

The code also recognizes these optional keys:

```yaml
sqlite:
  backup-directory: backups
  max-backups: 14
  busy-timeout-millis: 10000
pool:
  validation-timeout-millis: 5000
  idle-timeout-millis: 600000
retry:
  maximum-delay-millis: 2000
executor:
  threads: 8
  queue-capacity: 2048
shutdown-timeout-millis: 15000
```

SQLite enforces a single queue regardless of `executor.threads` and uses
WAL, `synchronous=NORMAL`, foreign keys and `busy_timeout`. A startup backup
is created only when a nonempty database exists. Disable periodic backups
with `backup-interval-hours: 0`.

MySQL/MariaDB use:

- `host`, `port`, `database`, `username`, `password`;
- `parameters` in `key=value&key2=value` format;
- pool `minimum-idle`, `maximum-size`, connection timeout and maximum lifetime.

Built-in backups are available only for SQLite. See
[MIGRATION.md](MIGRATION.md) for details.

## `plants.yml`

The `growth` section defines hourly health/hydration/nutrition loss,
stress recovery, minimum health for growth, the optimal hydration range,
and time until overripe and spoiled stages.

The `care` section defines watering-can, nutrient, cleaning-kit, spray and
repair strength. `problems` defines problem chance per update, minimum
spacing and allowed problems. `display.material-by-stage` maps the eight
stages to vanilla materials.

## `strains.yml`

Each entry in `strains` contains:

- a name and `rarity`;
- growth time, difficulty, yield and quality potential;
- resistance, water use, light preference, stress sensitivity and aroma;
- positive and negative effect profiles;
- seed price and base market price;
- model and particle-profile identifiers.

Built-in identifiers:

```text
berry_fog, concrete_kush, lemon_static, green_alley,
neon_haze, night_widow, purple_crash, velvet_smoke,
ghost_garden, royal_static, black_orchard, verdant_vice
```

An identifier is part of persistent data. Do not change it after production
has started. Change the displayed name through `display-name` instead.

## `genetics.yml`

This file contains mutation chance, cross stability penalty, name limits
and grade thresholds from `DAMAGED` to `LEGENDARY`. It is loaded and
validated like other YAML files, but the current domain genetics services
use policies constructed in code. Treat these entries as a balance catalog
for further integration, not a guaranteed switch for every calculation.

## `quality.yml`

`weights` combines genetics, care, health, harvest timing, cleanliness,
drying, curing and skills into a 0–100 score. `penalties` describes
contamination and spoilage. `grades` contains human-readable catalog thresholds.

The `processing` section is used for:

- updating jobs outside loaded chunks;
- the update interval;
- drying time, capacity, cleanliness, stability and speed;
- curing time, capacity, cleanliness and stability;
- grinding yield and risk;
- the maximum number of batches in a mixture;
- round time and the green window in the five-stage minigame.

The domain enum classifies scores using its own thresholds. Changing only
the `grades` map does not automatically change `QualityGrade.fromScore`.

## `effects.yml`

`enabled` disables use of effect products. Other sections limit duration,
amplifier, negative-effect risk, strong-effect blocking, and tolerance gain
and real-time decay. `safe-limits.minimum-health` prevents applying an
effect when health is too low.

## `villagers.yml`

- `herbalist.conversion-chance` is a one-time random check for a farmer;
- `max-per-village` applies within `village-radius`;
- `daily-buy-limit` limits sales;
- `levels` thresholds define herbalist tiers;
- `hiring-contract` defines the hiring-contract cost in emeralds, books
  and gold blocks.

The code also recognizes optional `offer-prices.<id>` and
`offer-prices.book-<id>` entries.

Novice herbalists sell the first-sale essentials, selection wand, manager
table and all care tools. Defaults are 4 emeralds for a wand, 16 for a manager
table and 24 for a drying rack. Curing unlocks at apprentice; grinding and
rolling supplies unlock at journeyman. Paper and filters cost 1 emerald each.
Purchases and sales both grant merchant experience. Shop pages keep additional
custom-strain offers accessible when the catalog exceeds one inventory page.

## `workers.yml`

This file controls plants per worker, reservation time, pathfinding attempts
and timeout, the daily schedule, salary, arrears thresholds, morale and task
type priorities. Schedule time is Minecraft world time, `0..23999`.

Do not set `task-reservation-seconds` shorter than the actual time needed
to reach the most distant target. Emergency teleportation is a separate
setting in `config.yml`.

## `economy.yml`

`provider: EMERALD` requires no dependencies. Payments that overflow the
inventory safely drop stacks near the player. For Vault, set
`provider: VAULT` or `vault.enabled: true` and install Vault and an economy
provider. `integrations.yml` must also allow `vault: true`. If the hook is
disabled, absent or has no registered economy service, Emerald remains the
provider; diagnostics report the provider actually in use.

Buy/sell multipliers and minimum/maximum prices are used for offers,
contracts and transactions.

## `market.yml` and `contracts.yml`

The market updates every 30 minutes by default. Prices are bounded by
minimum and maximum multipliers and affected by supply, demand, reversion
to the base price, repeated sales and random events.

Disable contracts with `enabled: false`. The file defines allowed types,
minimum/maximum deadlines, reward multiplier and reputation, and the active
contract limit.

### Accepted sale states

`market.yml` contains `trade.accepted-item-types`. By default, an herbalist
accepts `DRY_BATCH`, `CURED_BATCH` and `PACKAGE`. Administrators can
deliberately add `FRESH_BATCH`; its price then uses the actual `freshWeight`
and `trade.fresh-price-multiplier`. The signature, owner, `ProductBatch` record
and batch state are checked before an offer is created. Strict item-registry
reuse checks apply only when `security.anti-dupe-enabled` is `true`.

## `gui.yml`

GUI size must be a valid inventory size; the code normalizes it to a
multiple of 9. Slots must fit within their GUI. `page-size` limits entries
per page. The minigame uses `rolling` settings in this file and detailed
`processing.rolling` values in `quality.yml`.

## `items.yml`

Materials provide the vanilla fallback, while `custom-model-data` links an
item to the resource pack. The built-in item range is `12001..12025`.
Changing a material or number requires a corresponding definition change
in both pack variants. Existing signed items retain their saved PDC data.

## `integrations.yml`

Runtime adapters in this version:

- `vault`;
- `placeholderapi`;
- `worldguard`;
- `griefprevention`;
- `lands`.

These hooks require both their installed dependency and an enabled flag
(the default is `true`). Vault also requires the economy setting described
above. Vault and region hooks are detected again by `/vv admin reload`.
The PlaceholderAPI expansion is registered at startup: restart the server
after changing `placeholderapi` to enable or disable its registration.

WorldGuard checks its build permission and explicit session bypass.
GriefPrevention checks build permission in the claim at the target location.
Lands checks the world's `BLOCK_PLACE` role flag, including subareas and
wilderness restrictions, rather than treating land-level trust as permission.

`region-protection-fallback: DENY_IF_UNKNOWN` is fail-closed. Any other
value does not block solely because a response is `UNKNOWN`, but an
explicit integration denial still blocks the action.

The `itemsadder`, `oraxen` and `luckperms` flags are reserved and do not
start separate adapters in this version.

## `resourcepack.yml`

The pack is sent only when `enabled: true`, the URL is nonempty and `sha1`
contains exactly 40 hexadecimal digits. `required` is passed to Paper,
`send-on-join` sends the pack after joining, and `prompt-key` identifies
the MiniMessage message key.

`fallback-without-pack` is currently informational; the vanilla fallback
comes from item materials independently of this flag.

## `notifications.yml`

Supported types:

```text
PLANT_READY, DISEASE, LOW_WATER, DRYING_COMPLETE, CURING_COMPLETE,
UNPAID_WORKER, BLOCKED_WORKER, CONTRACT_COMPLETE, MARKET_CHANGE
```

Configurable channels include `CHAT`, `ACTION_BAR`, `TITLE` and `BOSS_BAR`.
A player can enable or disable individual types with a command. Sound
names must match `Sound` enum values in the current Paper version.

## Languages

Files in `languages/` use MiniMessage. The fallback chain is:

1. the player's language;
2. `default-language`;
3. `en_US`;
4. a red missing-key marker.

The player's language controls the GUI, books and messages.
`main.default-language` controls global entity names such as herbalist
names. Bukkit's `customName` is shared by all players, so it is not
localized separately for each observer. A safe reload refreshes the names
of already-loaded, marked herbalists.

`en_US.yml` is the required reference. Every other language must contain
all its text keys, or startup/reload fails. Placeholders such as `<player>`
are inserted as uninterpreted text, while MiniMessage formatting tags in
the template itself remain active.

## Signing key

`data/signing.key` is generated randomly (256 bits), saved atomically and,
where possible, restricted to its owner through filesystem permissions.
Do not edit, regenerate or share it. A key backup must match the database
backup; combining a key with an unrelated state causes items to be rejected.

## Worker reliability settings

`workers.yml` contains the bounded salary retry and diagnostic intervals:

```yaml
salary:
  retry-initial-seconds: 5
  retry-maximum-seconds: 60
  warning-interval-seconds: 60
state-transition-warning-interval-seconds: 60
```

Only transient connectivity/locking failures are retried. SQL integrity
violations are classified as permanent and disable a stale runtime after its
active task is released. The intervals limit repeated diagnostics; they do not
suppress the first error.
