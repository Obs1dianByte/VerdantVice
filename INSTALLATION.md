# Installing VerdantVice

## 1. Choose the environment

Supported configurations:

- Paper/Purpur 1.21.4 on Java 21;
- Paper/Purpur 26.2 on Java 25.

The same production JAR works in both configurations. The plugin does not
claim support for Folia, Spigot or CraftBukkit. Before installation, make
a complete backup of the world, the `plugins/` directory and the server
database.

## 2. Basic installation with SQLite

1. Stop the server.
2. Copy `VerdantVice-1.0.0.jar` to `plugins/`.
3. Remove any `VerdantVice-1.0.0-thin.jar`; it must not be installed on the
   server.
4. Start the server.
5. Watch the console until this message appears:

   ```text
   Plugin enabled successfully.
   ```

6. Run `/vv admin database`. The expected type is `SQLITE`, and the database
   state should be accepting work.
7. Join as a player and check `/vv guide`.

The first startup creates:

```text
plugins/VerdantVice/
├── *.yml
├── languages/
│   ├── en_US.yml
│   └── ...
├── data/
│   ├── verdantvice.db
│   ├── signing.key
│   └── processing-stations.yml
├── backups/
└── logs/
```

`processing-stations.yml` is created only after a station has been saved.
SQLite may have `-wal` and `-shm` files while running; do not copy only the
live `.db` file without a safe backup mechanism.

## 3. Installation with MySQL or MariaDB

First create an empty database and an account allowed to create and modify
tables in that database. VerdantVice uses InnoDB tables and `utf8mb4`.

1. Start the plugin once to generate its files, then stop the server.
2. Set `type: MYSQL` or `type: MARIADB` in
   `plugins/VerdantVice/database.yml`.
3. In the corresponding section, enter `host`, `port`, `database`,
   `username`, `password` and JDBC parameters.
4. Do not publish this file or reports containing its contents.
5. Start the server and check migrations and `/vv admin database`.

Example:

```yaml
type: MARIADB
mariadb:
  host: db.internal.example
  port: 3306
  database: verdantvice
  username: verdantvice
  password: "long-random-password"
  parameters: useUnicode=true&characterEncoding=utf8&useSSL=true
```

Changing the type or connection details always requires a full restart.
`/vv admin reload` deliberately skips `database.yml`.

VerdantVice does not automatically transfer data between SQLite, MySQL and
MariaDB. Changing the type without migrating data creates an independent
game state.

## 4. Optional plugins

All integrations are soft dependencies: a missing plugin does not prevent
VerdantVice from loading.

- Vault also requires a compatible economy provider. Set
  `economy.yml` → `provider: VAULT` or `vault.enabled: true`.
- PlaceholderAPI exposes placeholders when the plugin is detected.
- WorldGuard, GriefPrevention and Lands are detected when the corresponding
  entry in `integrations.yml` is `true`.

If a protection adapter fails, its decision becomes `UNKNOWN`. The default
`region-protection-fallback: DENY_IF_UNKNOWN` safely blocks building in this
case.

## 5. Resource pack

The pack is not part of the server JAR and is optional. Choose the variant
in `resource-pack/1.21.4/` or `resource-pack/26.2/`, ZIP its contents, host the
ZIP at a public HTTPS address and configure `resourcepack.yml`.

For instructions, including SHA-1 calculation, see
[RESOURCE_PACK.md](RESOURCE_PACK.md).

## 6. Initial configuration

Before opening the server to players:

1. choose `default-language`;
2. check plant, farm, worker and display limits in `config.yml`;
3. choose the economy in `economy.yml`;
4. set region rules in `integrations.yml`;
5. adjust the SQLite backup interval;
6. configure permissions in your permissions plugin;
7. complete the full scenario in [TESTING.md](TESTING.md).

## 7. Updating

1. Stop the server; do not use `/reload`.
2. Back up the following together:
   - the database or a consistent dump,
   - `data/signing.key`,
   - `data/processing-stations.yml`,
   - all YAML files and languages.
3. Replace the JAR with the new production JAR.
4. Start the server and allow automatic migrations to run.
5. Check the log, `/vv admin database`, `/vv admin performance` and the
   basic gameplay cycle.

Do not modify `vv_schema_history` manually. For the complete procedure, see
[MIGRATION.md](MIGRATION.md).

## 8. Moving to another host

Move the entire `plugins/VerdantVice/` directory, not just the database.
`data/signing.key` is particularly important: it authenticates existing
items, batches and operations. Set file permissions so that the server
account can read and write the directory, but other host users cannot read
the key or database password.

After moving a network database, change only the connection details and
restart. For SQLite, verify that the path remains inside
`plugins/VerdantVice/`; the code rejects paths outside the plugin directory.

## 9. Uninstalling

Stop the server, remove the JAR and archive the data. Deleting
`plugins/VerdantVice/` permanently removes profiles, farms, plants, batches,
the market, contracts and the signing key. Before doing so, retain a backup
consistent with the server's privacy policy.
