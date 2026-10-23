# Migrations, updates and data backups

## Migration model

VerdantVice runs migrations automatically during asynchronous startup,
before registering the public API and announcing readiness. History is
stored in:

```text
vv_schema_history
```

Each entry stores a version, description, SQL SHA-256, installation time,
execution duration and success flag. The version catalog is contiguous.

Current schema:

| Version | Scope |
|---:|---|
| 1 | Players, skills, tolerance, farms, members, storages, genetics, strains, plants and plant events |
| 2 | Batches, drying, curing, herbalists, workers, tasks, salaries, contracts, trading and market |
| 3 | Atomic `item_registry` and idempotent `operation_journal` |
| 4 | Durable worker harvest delivery outbox, `worker_harvest_deliveries` |
| 5 | Atomic worker processing delivery outbox, `processing_deliveries` |

Never edit an already released migration. A checksum change, gap, unknown
version or `success = 0` entry stops startup rather than risking data
corruption.

## What to back up

The complete state consists of:

- the SQLite database or MySQL/MariaDB database;
- `plugins/VerdantVice/data/signing.key`;
- `plugins/VerdantVice/data/processing-stations.yml`;
- configuration files and custom translations;
- optionally, diagnostic logs.

A SQL dump alone is not enough for a full restore. `signing.key` must match
the database because it signs item and batch identities.
`processing-stations.yml` stores physical station positions, while batches
and jobs are stored in SQL.

## SQLite backups

With the default `backup-on-start: true`, the plugin:

1. checks whether a nonempty database file exists;
2. opens the database with WAL;
3. performs a passive checkpoint;
4. creates a consistent copy with SQLite `VACUUM INTO`;
5. atomically moves the `.partial` file to the backup directory;
6. removes backups beyond the retention limit.

Names look similar to:

```text
verdantvice-20260727-120000-000-startup.db
verdantvice-20260727-180000-000-manual.db
```

Periodic backups default to a 12-hour interval. The code recognizes
`sqlite.max-backups`, which defaults to 14. Built-in backups do not include
`signing.key`, stations or YAML files; archive the entire directory
separately.

## MySQL/MariaDB backups

The plugin does not create network database dumps. Use the database
administrator's backup tools and policy. A dump should be transactionally
consistent and preserve `utf8mb4` encoding, indexes, foreign keys and the
history table.

Before updating, stop the server or ensure a consistent snapshot of the
database and key file. Do not run two instances migrating the same database
at the same time.

## Safely updating the JAR

1. Read the release notes and Java/Paper requirements.
2. Run `clean build` and retain the production JAR.
3. Stop the server.
4. Make the complete backup described above.
5. Replace only the VerdantVice JAR; remove old copies and `-thin.jar`.
6. Do not blindly copy default YAML files over existing files.
7. Start the server.
8. Check each `Applying database migration ...` log entry, the schema
   result and `Plugin enabled successfully.`.
9. Run `/vv admin database` and test reading an existing plant, batch, farm,
   contract and worker.
10. Keep the backup until the observation period ends.

`/vv admin migrate` invokes the current database manager, which retains its
initialization future. It does not apply migrations from a new JAR without
a restart. Plugin-version migrations always run during a full startup.

## Restoring SQLite

1. Stop the server and confirm that no Java process is using the database.
2. Preserve the damaged directory for analysis.
3. Choose a backup matching the signing-key and station backups.
4. Replace `data/verdantvice.db` with the selected backup.
5. Do not transfer old `verdantvice.db-wal` or `verdantvice.db-shm` files.
6. Restore the corresponding `signing.key` and `processing-stations.yml`.
7. Start the server and check history/migrations.
8. Run the integrity scenario before reopening the server to players.

Do not manually delete a `success = 0` entry. There is no guarantee about
which DDL statements a particular engine executed before rollback.

## Restoring MySQL/MariaDB

1. Stop the server.
2. Restore the dump into an empty database, or replace the entire database
   in a controlled manner.
3. Restore the key and stations from the same point in time.
4. Verify the account, TLS/parameters and table permissions.
5. Start VerdantVice and check `vv_schema_history`.

If the database contains a version newer than the catalog in the JAR, the
plugin is too old. Install a matching or newer version; do not lower the
version number manually.

## Changing the database engine

Changing `type` does not copy data. VerdantVice has no automatic
SQLite ↔ MySQL ↔ MariaDB converter. Migration between engines requires
a controlled export/import preserving:

- text UUIDs;
- epoch milliseconds in timestamp fields;
- JSON;
- all constraints and indexes;
- `row_version`;
- schema history;
- the item registry and operation journal.

Test the conversion offline on a copy, and do not run the old and new
engines simultaneously.

## Downgrading

Migrations are one-way. An older JAR rejects an unknown schema version.
The only safe downgrade path is restoring a complete backup made before
the update, including the key, stations and matching JAR.

## Worker reliability schema 4

Schema migration 4 adds `worker_harvest_deliveries`, a durable outbox between
the atomic plant/batch transaction and Bukkit inventory delivery. Startup runs
a worker integrity pass before runtime maps are populated. When SQLite repairs
interrupted task pointers, a standard database backup is created first. Valid
worker UUIDs, entity UUIDs, wages, morale, experience and storage assignments
are preserved.
