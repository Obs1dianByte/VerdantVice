# VerdantVice development

## Architecture

The project is a multi-module Gradle build:

```text
VerdantVice
├── verdantvice-core
│   ├── src/main/java
│   └── src/test/java
├── verdantvice-paper
│   ├── src/main/java
│   ├── src/main/resources
│   └── src/test/java
└── resource-pack
```

`verdantvice-core` contains the immutable domain model and calculation
services without Bukkit dependencies: plants, genetics, processing, effects,
tolerance, the market, contracts, farms, and workers.

`verdantvice-paper` is the runtime adapter: plugin bootstrap, listeners,
GUI, entities and display entities, PDC, commands, SQL repositories,
integrations, localization, and the public API.

## Tools and versions

- Java source/target/release: 21;
- Gradle wrapper: 9.6.1;
- Compilation Paper API: 1.21.4;
- JUnit Jupiter: 5.13.4;
- Shadow: 9.6.1;
- run-paper: 3.0.2;
- HikariCP: 6.3.3;
- SQLite JDBC: 3.50.3.0;
- MySQL Connector/J: 9.4.0;
- MariaDB Java Client: 3.5.6;
- Gson: 2.13.2.

Runtime dependencies are shaded into the production JAR; Hikari and Gson
packages are relocated. Paper and PlaceholderAPI remain `compileOnly`.

## Domain layer rules

- Models are immutable. Operations return a new object that the caller must
  persist.
- Pass time explicitly as `Instant`; tests must not depend on the system
  clock.
- Accept randomness as a parameter or isolate it in an adapter so tests
  remain deterministic.
- Validate ranges and UUIDs at model boundaries.
- Core code must not import Bukkit/Paper or SQL classes.
- A batch state change invalidates its previous signature and requires
  re-signing by the layer authorized to persist it.

## Threads and the server

Worlds, entities, inventories, and GUI must be modified only on the main
thread. Repositories return `CompletableFuture` and execute SQL on a bounded
executor. After a database operation completes, the adapter returns to
Bukkit through the scheduler.

Do not call `join()` or `get()` on the main thread. Do not execute SQL during
a server tick. Economy operations, including Vault operations, deliberately
run on the main thread.

Plant and worker updates must stay batched according to configuration
limits. Do not iterate over every world record in a single tick.

## Durability and idempotency

Repositories use `row_version`, transactions, reservations, and CAS checks.
`item_registry` is the source of truth for item instance state, while
`operation_journal` protects against repeated operations. A new trade or
processing path should:

1. Verify the PDC signature.
2. Reserve the instance in the database.
3. Perform the mutation atomically and idempotently.
4. Mark the input as consumed.
5. Register and deliver the output.
6. Safely restore the input after an unambiguous rollback.

Never create a stateful product with a plain `new ItemStack` without
`SecureItemService`.

## Database migrations

Migrations are defined in `MigrationCatalog` with consecutive versions and
no gaps. Each has a SHA-256 derived from its number, description, and SQL.

- Never edit a migration that has already been released.
- Append a new version to the catalog.
- Provide SQL variants for SQLite, MySQL, and MariaDB.
- Use `utf8mb4` and InnoDB for network databases.
- Add tests for a clean migration and an upgrade from the previous version.
- Do not hide a partially applied migration; startup should fail safely.

The current schema version is 5. See [MIGRATION.md](MIGRATION.md).

## Configuration

For each new configuration file:

1. Add it to the resources.
2. Add it to `ConfigManager.CONFIG_FILES`.
3. Copy it only if it does not already exist.
4. Validate it before replacing the active snapshot.
5. Document it in [CONFIGURATION.md](CONFIGURATION.md).

If a change cannot be applied safely at runtime, add the file to the list
that requires a restart. Do not include passwords or secrets in diagnostics.

## Localization

`en_US.yml` is the canonical key set. Each of the other five languages must
contain all its text values. Update all six files when adding a key.

Messages use MiniMessage. Pass user data as `Placeholder.unparsed` rather
than concatenating it into the template. Keep placeholder names consistent
across translations.

## Public API

Do not expose repositories or mutable caches. New API should:

- Return a snapshot or `CompletableFuture`.
- Explicitly document thread behavior.
- Maintain `API_VERSION` compatibility.
- Emit an event for a significant mutation.
- Never expose signatures, secrets, or connection details.

The v1 contract is documented in [API.md](API.md).

## Resource pack

`CustomModelData` numbers in `items.yml`, definitions in
`assets/minecraft/items/*.json`, and models in both variants must remain
consistent. Atlas changes should regenerate 1.21.4 and 26.2 together.
See [RESOURCE_PACK.md](RESOURCE_PACK.md).

## Change checklist

1. Add or update a domain test.
2. For SQL changes, test all three dialects or explicitly limit the scope.
3. Run `clean build`.
4. Check that the main thread is not blocked.
5. Check a restart during active drying, a contract, and a worker task.
6. Check repeated-click and repeated-packet scenarios.
7. Update all six languages and the documentation.
8. Run Paper 1.21.4/Java 21 and Paper 26.2/Java 25 manually.
9. Verify the production JAR's contents, not just `-thin.jar`.

Test commands and reports are documented in [TESTING.md](TESTING.md).
