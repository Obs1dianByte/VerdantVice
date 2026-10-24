# Optional-provider acceptance tests

This probe runs only on a stopped/prepared `build/minecraft-client/` test server
bound to `127.0.0.1`. It waits for the real `VVClientTest` client to connect. It
does not create fake players or substitute plugins for the actual providers.

Run the base gameplay test first. Stop its isolated server, then install the
providers and this separate probe:

```powershell
pwsh -File scripts/Prepare-IntegrationClientTest.ps1 -TestDirectory <isolated-test-directory>
```

Restart the test server and reconnect the same client. No launcher credentials,
user worlds, production databases or paid-plugin license checks are accessed.
The probe restores the account balance, operator status, temporary permissions,
temporary WorldGuard region and GriefPrevention claim that it changes.

## Exact provider versions

Download these files from their publishers into `.gradle/integration-plugins/`.
The setup script verifies the recorded SHA-256 values before copying them. It
does not download or redistribute third-party plugin binaries.
`integration-provider-manifest.json` records the prepared production JAR hash
and exact provider hashes; the runtime JSON/JUnit reports remain separate.

| File | Version | Official source |
| --- | --- | --- |
| `Vault.jar` | 1.7.3 | [MilkBowl release](https://github.com/MilkBowl/Vault/releases/tag/1.7.3) |
| `PlaceholderAPI.jar` | 2.12.3 | [PlaceholderAPI release](https://github.com/PlaceholderAPI/PlaceholderAPI/releases/tag/2.12.3) |
| `EssentialsX.jar` | 2.22.0 | [EssentialsX release](https://github.com/EssentialsX/Essentials/releases/tag/2.22.0) |
| `worldedit-bukkit-7.4.6.jar` | 7.4.6 | [EngineHub WorldEdit](https://modrinth.com/plugin/worldedit/version/YFnqiUlL) |
| `worldguard-bukkit-7.0.19.jar` | 7.0.19 | [EngineHub WorldGuard](https://modrinth.com/plugin/worldguard/version/TtfwTyi6) |
| `GriefPrevention.jar` | 16.18.7 | [GriefPrevention](https://modrinth.com/plugin/griefprevention/version/dGfCZHqk) |

Publisher-provided release SHA-256 digests were checked for PlaceholderAPI and
EssentialsX; publisher-provided SHA-512 digests were checked for the three
Modrinth files. Vault's older GitHub release has no publisher-provided digest;
its HTTPS download was locally SHA-256-pinned by the setup script.

## Coverage and boundaries

- Vault with the actual EssentialsX economy: detection, deposit, balance,
  withdrawal, insufficient funds, zero/negative input, disable/re-enable, restore.
- PlaceholderAPI: the plugin's registered expansion, farm/plant/worker/profile
  values, market formatting, unknown parameters and persistence across reload.
- WorldGuard with WorldEdit: real region membership, outsider denial, wilderness,
  explicit bypass, bypass removal and adapter configuration.
- GriefPrevention: real claim creation, outsider denial, build trust and wilderness.
- Multiple active providers: a denial from either protection adapter still blocks.

Results are written to `plugins/VerdantViceIntegrationProbe/` as
`integration-results.json`, `junit.xml` and, only on success, `integration.pass`.
Merely installing this probe is not evidence of a passing runtime test.

Lands has API-level regression tests against the documented role-flag contract,
not licensed-plugin runtime coverage. Its implementation follows the
[official Lands integration API](https://wiki.incredibleplugins.com/lands/developers/api).
WorldGuard bypass follows its
[official protection-query guidance](https://worldguard.enginehub.org/en/latest/developer/regions/protection-query/).
ItemsAdder, Oraxen and LuckPerms-specific setups are not exercised by this probe.
Do not label those configurations as runtime-tested without their actual plugins.
