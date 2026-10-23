# VerdantVice public API

## Contract and availability

The public entry point is:

```java
com.verdantvice.api.VerdantViceApi
```

The current value of `VerdantViceApi.API_VERSION` is `1`. The implementation
is registered with `ServicePriority.Normal` in Bukkit's `ServicesManager`
and unregistered when the plugin shuts down.

Important: VerdantVice starts its database and loads data asynchronously.
The service is registered only after that process completes. Even a plugin
with `depend: [VerdantVice]` may receive `null` during its own `onEnable()`.
Resolve the service lazily, retry after startup, or disable the integration
feature until the service becomes available.

Safe lookup example:

```java
import com.verdantvice.api.VerdantViceApi;
import org.bukkit.Bukkit;

import java.util.Optional;

public Optional<VerdantViceApi> verdantViceApi() {
    return Optional.ofNullable(
            Bukkit.getServicesManager().load(VerdantViceApi.class)
    );
}
```

Do not cast the plugin's main class or access its private managers.

## Consumer plugin dependency

Add the following to the consumer's `plugin.yml`:

```yaml
depend:
  - VerdantVice
```

For optional integrations, use `softdepend` and handle an unavailable service.
This repository does not publish a VerdantVice artifact to an external Maven
repository. During local development, compile against the production JAR:

```kotlin
dependencies {
    compileOnly(files("libs/VerdantVice-1.0.0.jar"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}
```

Consumers should generate Java 21 bytecode to run on both supported server
lines.

## API v1 methods

| Method | Result | Behavior |
|---|---|---|
| `apiVersion()` | `int` | Contract version, currently 1 |
| `plugin()` | `Plugin` | Service owner |
| `profile(UUID)` | `CompletableFuture<PlayerProfile>` | Loads a profile; new profiles use the configured server locale, which defaults to `en_US` |
| `farm(UUID)` | `Optional<Farm>` | In-memory farm snapshot |
| `playerFarm(UUID)` | `Optional<Farm>` | Farm owned by the player |
| `plant(UUID)` | `Optional<Plant>` | In-memory plant snapshot |
| `strain(String)` | `Optional<Strain>` | Strain lookup |
| `createContract(UUID, UUID)` | `CompletableFuture<StoredContract>` | Generates a player–herbalist contract |
| `marketQuote(String, double, double, double)` | `CompletableFuture<MarketQuote>` | Quote for a strain, quality, freshness, and reputation |
| `currentPrice(String)` | `CompletableFuture<Double>` | Reference price at quality 50, freshness 100, and reputation 0 |
| `registerStrain(Strain)` | `CompletableFuture<Void>` | Adds a strain to memory and initializes its market record |

All methods except `plugin()` and `apiVersion()` require VerdantVice to be
ready. Calling them before readiness throws `IllegalStateException` or
completes the operation's future exceptionally.

Domain objects returned by the API are immutable snapshots. Replacing a
local reference does not update the database or cache.

## Asynchronous operations

Methods returning `CompletableFuture` may complete off the main thread.
Do not modify the Bukkit world, entities, inventories, or GUI directly in
their callbacks. Schedule that work through the consumer plugin's scheduler:

```java
VerdantViceApi api = verdantViceApi().orElseThrow();

api.currentPrice("berry_fog").whenComplete((price, failure) -> {
    Bukkit.getScheduler().runTask(this, () -> {
        if (failure != null) {
            getLogger().warning("Could not fetch the price: "
                    + failure.getMessage());
            return;
        }
        getLogger().info("Blueberry price: " + price);
    });
});
```

Quality, freshness, and reputation ranges are validated by the domain layer.
Do not assume that an arbitrary `double` will be clamped automatically.

## Bukkit events

All events are in `com.verdantvice.api.event`.

| Event | Data | Cancellable |
|---|---|---:|
| `VerdantPlantPlacedEvent` | Player, plant ID, location, strain | Yes |
| `VerdantPlantStageChangeEvent` | Plant ID, previous and next stage | Yes |
| `VerdantPlantDiseaseEvent` | Plant ID, problem ID, severity | No |
| `VerdantPlantHarvestEvent` | Player, plant ID, batch ID, harvest timing | Yes |
| `VerdantBatchDryEvent` | Batch ID, drying stage | No |
| `VerdantBatchCureEvent` | Batch ID, curing stage | No |
| `VerdantProductUseEvent` | Player, item instance ID, batch ID | Yes |
| `VerdantHerbalistCreatedEvent` | Herbalist entity | No |
| `VerdantMarketPriceChangeEvent` | Strain, previous and next price | Yes |
| `VerdantContractCompleteEvent` | Contract ID, owner UUID | No |
| `VerdantWorkerHiredEvent` | Owner, worker entity, farm ID | Yes |
| `VerdantWorkerTaskEvent` | Worker ID, task ID, worker state | No |

Example:

```java
import com.verdantvice.api.event.VerdantPlantHarvestEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class HarvestListener implements Listener {
    @EventHandler(ignoreCancelled = true)
    public void onHarvest(VerdantPlantHarvestEvent event) {
        getLogger().info(event.player().getName()
                + " harvested batch " + event.batchId()
                + " during " + event.timing());
    }
}
```

Register the listener normally through `PluginManager`. Cancelling a
cancellable event affects gameplay: do so only for a clear reason, and do
not perform long database operations in the handler.

## API stability

API v1 covers only the `VerdantViceApi` interface, the types returned by its
signatures, and the listed events. Managers, repositories, GUI and security
classes, and `DefaultVerdantViceApi` are implementation details.

Before using a feature that depends on a newer version, check:

```java
if (api.apiVersion() < 1) {
    // Disable the integration.
}
```

Do not retain a service instance after VerdantVice shuts down. Resolve it
again after a full server reload.
