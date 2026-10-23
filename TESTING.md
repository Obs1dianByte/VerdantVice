# Testing

Run commands from the repository root. Use isolated worlds and databases;
never test item corruption or migration failures against production data.

## Build checks

Windows:

```powershell
.\gradlew.bat clean build
```

Linux/macOS:

```bash
./gradlew clean build
```

Use JDK 21 or newer. The build runs Core and Paper JUnit suites, then checks
the shaded JAR and both resource-pack ZIPs. It verifies required classes,
resources, JDBC providers and archive contents; compiling alone is not a
release check. Install only `VerdantVice-1.0.0.jar`, not `-thin.jar`.

The suites cover domain gameplay, real SQLite transactions, rollback and
concurrent item consumption, durable processing/genetics deliveries, worker
hiring and salaries, configuration migration, translations and pack assets.
These tests do not establish client rendering or third-party compatibility.

To run only one module:

```powershell
.\gradlew.bat :verdantvice-core:test
.\gradlew.bat :verdantvice-paper:test
```

Reports:

- `verdantvice-core/build/reports/tests/test/index.html`
- `verdantvice-paper/build/reports/tests/test/index.html`
- JUnit XML under each module's `build/test-results/test/`

## MySQL and MariaDB

With a running Docker engine:

```powershell
.\gradlew.bat :verdantvice-paper:networkDatabaseTest
```

The task uses disposable MySQL 8.4 and MariaDB 11.4 containers and the
production database code. It checks migrations, rollback, concurrent
reservations, single consumption and persistence after repository restart.
Containers are removed after the test.

Without Docker, the local task reports `SKIPPED`. A skip is not a passing
database test. CI treats unavailable Docker as a failure. Reports are in
`verdantvice-paper/build/reports/tests/networkDatabaseTest/`.

## Real Paper startup and restart

Requirements:

- PowerShell 7 and JDK 25;
- a Paper 26.2 bootstrap and its runtime cache;
- an EULA file already accepted by the server owner.

Run the build and isolated server check together:

```powershell
pwsh -File scripts/Verify.ps1 -JavaHome 'C:\path\to\jdk-25'
```

Use `-SkipBuild` only when the selected artifact has already been built and
tested. The script discovers JDK 25 through `JAVA25_HOME`, `JAVA_HOME`, or
`.gradle/runtime-tools/jdk-25*`. Environment changes are process-local. It
does not download a JDK or accept the Minecraft EULA.

By default, the server check reads an existing Paper bootstrap from the
Gradle run-paper cache, runtime assets from `run/`, and `run/eula.txt`.
It copies no user world, database, signing key or plugin configuration.
Paperclip may download missing runtime files for the selected bootstrap.
Inputs can also be supplied explicitly:

```powershell
pwsh -File scripts/Test-PaperSmoke.ps1 `
  -JavaHome 'C:\path\to\jdk-25' `
  -BootstrapJar 'C:\test-assets\paper-26.2.jar' `
  -RuntimeCacheDirectory 'C:\test-assets\paper-cache' `
  -EulaFile 'C:\test-assets\accepted-eula.txt' `
  -PluginJar 'verdantvice-paper/build/libs/VerdantVice-1.0.0.jar'
```

Each run starts two isolated Paper processes sequentially on a loopback
port, with a controlled shutdown between them. The probe checks:

- startup, migrations, configuration generation, API and console commands;
- real Bukkit items, PDC, signatures, owner checks and repeat consumption;
- database rollback after an injected consumption failure;
- GUI inventory holders and plant display/interaction entities;
- signed storage delivery after a world-save barrier;
- profiles, items, signing keys, herbalists and chest contents after restart;
- manual and startup SQLite backups.

Exit code `0` means success; a failure or timeout returns `1` and stops the
owned server process. `build/paper-smoke/<identifier>/` retains `junit.xml`,
`summary.json`, both server logs and isolated data. The summary records the
tested artifact's SHA-256. Do not run `clean` concurrently with this test:
it removes the build directory containing its reports and server files.

The probe runs without a player. It does not test client rendering, physical
GUI clicks, external economy/region providers or every worker behavior.
Never install the test-only `VerdantViceSmoke` plugin on a public server.

## Minecraft client checks

The client harness requires Windows, PowerShell 7, JDK 25, an installed
vanilla Minecraft Java 26.2 client, cached Paper runtime and an accepted
`run/eula.txt`. Build the distribution first, then prepare the environment:

```powershell
pwsh -File scripts/Start-MinecraftClientTest.ps1 -JavaHome 'C:\path\to\jdk-25'
```

The script prints a new `build/minecraft-client/<identifier>` directory.
Start its `server.jar` with JDK 25 from that directory, keeping standard
input open for graceful shutdown. Then launch the isolated client:

```powershell
pwsh -File scripts/Start-MinecraftClientTest.ps1 `
  -JavaHome 'C:\path\to\jdk-25' `
  -LaunchClient -TestDirectory '<test-directory>'
```

It uses a disposable, unauthenticated identity on a loopback-only server.
It does not read launcher accounts or tokens, change the normal profile,
copy user worlds, download software or accept the EULA. Never expose this
offline-mode configuration to a network.

In the connected game:

1. Run `/vvclient check` and wait for the signed-item checks.
2. Run `/vvclient gallery` and inspect the item textures in-game.
3. Open `/vvclient menu MAIN`, navigate the production menus and perform
   protected action clicks. Change language and check the rendered text.
4. Run `/vvclient scenario setup`. The driver creates a new isolated farm,
   four authenticated seed plants and real processing stations. Physically
   right-click the first plant with the supplied watering can.
5. Run `/vvclient scenario process`. The production scheduler advances the
   plants; production managers harvest four batches, dry and cure them,
   grind two, mix them, and open the real rolling minigame. Wait for the
   inventory to appear; this takes approximately 70 seconds.
6. Click the five highlighted rolling buttons in order: inventory slots
   `10`, `12`, `14`, `16`, `13`. Aim for about one second between clicks.
   Run `/vvclient scenario rolled`, physically right-click the supplied
   rolled item, wait two seconds, then run `/vvclient scenario used`.
7. Run `/vvclient scenario economy`. The driver sells a genuine processed
   harvest, completes and claims a matching contract, checks repeat-claim
   protection, crosses signed parent seeds, hires a worker, and waits for
   an actual worker AI watering task and salary payment.
8. After the checkpoint message, run `/vvclient checkpoint`. Restart the
   **same** isolated server and reconnect. Run `/vvclient scenario restart`
   **before** `/vvclient check`, because the latter clears the inventory.
   Restart checks require unchanged farm/worker/seed identities, consumed
   product and contract records, persisted tolerance and no repeated salary
   debit or watering resource consumption.
9. Run `/vvclient check` again, open `/vvclient menu MAIN` and click a real
   production action. Run `/vvclient finish` to write the final report and
   stop the isolated server.

The gameplay driver labels direct production manager calls `SERVER_API`
and actual client input/outcomes `ACTUAL_CLIENT_*`. It creates no fake
players or synthetic inventory-click events. This is mixed UI/server
acceptance, not a claim that every game action was clicked by a human.

Timing fixtures are restricted to a child of `build/minecraft-client/`:
two in-memory strain growth durations become one second, plant updates
run every second, random plant problems are suppressed during this short
growth check, drying/curing targets become one minute, drying speed becomes
four and processing updates run every second. Existing harvest windows
remain unchanged, so these are early harvests rather than an optimal-window
balance test. Original strain values and the plant scheduler are restored
before harvest; processing settings are restored after product use. No
default configuration file is rewritten. Contract requirements are a
deterministic test fixture matching the fourth real harvested batch; its
creation, delivery, signature checks, completion and claim use production
repositories/services.

The probe requires successful item checks and real inventory open/click
events before it passes. Reports go to
`plugins/VerdantViceClientProbe/` inside the test directory. First-boot
checkpoint reports are retained under `session-reports/` when the probe
starts again; the final summary covers restart checks. Screenshots go
to that run's `client/screenshots/`. Event counts alone do not establish
correct visual rendering; inspect the actual client image.

Report expected offline-client Mojang/Realms authentication failures and
host performance-counter warnings separately from plugin exceptions.
Do not discard an unexplained error simply because the probe passed.

## Gameplay acceptance

Use a fresh test configuration and a separate account for access checks.
Record expected and actual results, the exact JAR hash, server/Java
versions, enabled providers and relevant logs.

| Area | Required checks |
|---|---|
| Startup | Clean install, existing schema, restart, SQLite backup |
| Language | All menus, six languages, reconnect persistence, missing keys |
| Farm | Region selection, overlap/size limits, roles, storage permissions |
| Plant | Care tools, spacing/limits, harvest windows, chunk unload/reload |
| Processing | Dry/cure/grind/mix/roll, restart mid-job, retrieve once |
| Recovery | Full inventory, disconnect/death/GUI close, occupied station |
| Security | Altered PDC, wrong owner, copied identity, concurrent clicks |
| Trading | Purchases/sales, reputation, price changes, limits, provider errors |
| Contracts | Partial delivery, restart, completion and reward exactly once |
| Workers | Hire, limits, tasks, blocked paths, salaries, interrupted tasks |
| Effects | Single use, tolerance, decay across restart, configured limits |
| Pack | Matching client version, accept/reject, invalid URL/hash, fallback |

For workers, include watering, harvesting, loading/unloading, planting,
packaging and cleaning. Check that retry paths neither lose inputs nor
create duplicate outputs. Preserve the database and signing key together
when checking restarts.

## Optional integrations

First test without optional plugins. Then test each provider separately,
recording its exact version; successful startup alone is not compatibility.

After the base real-client gameplay run, stop that isolated server, move
`VerdantViceClientProbe.jar` out of its `plugins/` directory, then prepare a
separate integration profile:

```powershell
pwsh -File scripts/Prepare-IntegrationClientTest.ps1 `
  -TestDirectory '<stopped-isolated-test-directory>'
```

The script installs six exact, hash-checked provider JARs from the local
cache and compiles the separate integration probe. It does not download
them, start a server or change the tested production JAR. See
[the integration probe instructions](scripts/integration-smoke/README.md)
for official download sources and versions. Restart the isolated server
and reconnect the real `VVClientTest` client. The probe starts automatically;
only a fresh `integration.pass` with a matching JSON/JUnit report establishes
success. Preparation or compilation alone is not a passing runtime test.

The probe exercises actual Vault/EssentialsX transactions, the registered
PlaceholderAPI expansion and real WorldGuard/GriefPrevention protection
decisions. It restores temporary account, region, claim and permission
changes. It does not cover every shop/contract error path or licensed Lands
runtime. Lands role-flag regression tests are API-contract tests, not proof
of compatibility with an installed Lands server.

- **Vault and an economy provider:** purchases, sales, insufficient funds,
  withdrawals/deposits, provider failure and rollback behavior.
- **PlaceholderAPI:** each documented `%verdantvice_*%` placeholder for
  online/offline players and missing data.
- **WorldGuard, GriefPrevention, Lands:** allowed and denied build/use
  actions, farm creation, role changes and overlapping protected regions.
- **Resource pack:** vanilla fallback, correct model variants and rejection
  behavior with optional/required pack settings.

ItemsAdder, Oraxen and LuckPerms have no dedicated runtime adapters in this
version. Do not report those adapters as tested integrations.

## Compatibility and load

Run the gameplay scenario on Paper/Purpur 1.21.4 with Java 21 and on 26.2
with Java 25, using matching packs and separate databases/worlds. The
`runServer` task and automated smoke harness target only Paper 26.2.

For load checks, approach configured plant, worker and display limits in
stages. Observe tick times, chunk loading, memory, database queue and
`/vv admin performance`. Check `/vv admin database` for queue growth.
Record concurrency, duration, hardware and configuration; do not infer
multi-player capacity from unit tests or a single-client session.

## CI and release checks

`.github/workflows/verify.yml` builds on Windows/Linux with JDK 21/25 and
runs network database tests in a separate Linux job. Reports and release
archives are attached to the workflow run. A configured workflow is not
evidence that its remote jobs have passed: inspect the run being published.

Before releasing, run a clean build, test that exact JAR on the target
server and preserve its hash with the reports. Note untested provider
combinations, skipped checks and compatibility limits in the release notes.
Do not publish worlds, database files, keys, test probes or runtime caches.
