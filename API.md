# Publiczne API VerdantVice

## Kontrakt i dostępność

Publicznym punktem wejścia jest:

```java
com.verdantvice.api.VerdantViceApi
```

Aktualna wartość `VerdantViceApi.API_VERSION` to `1`. Implementacja jest
rejestrowana z priorytetem `ServicePriority.Normal` w Bukkit
`ServicesManager`, a przy wyłączeniu pluginu wyrejestrowywana.

Ważne: VerdantVice uruchamia bazę i ładuje dane asynchronicznie. Rejestracja
usługi następuje dopiero po zakończeniu tego procesu. Nawet plugin z
`depend: [VerdantVice]` może podczas własnego `onEnable()` zobaczyć `null`.
Pobieraj usługę leniwie, ponów próbę po starcie albo wyłącz własną funkcję do
czasu jej dostępności.

Przykład bezpiecznego pobrania:

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

Nie rzutuj głównej klasy pluginu i nie pobieraj jego prywatnych managerów.

## Zależność pluginu konsumenta

Do `plugin.yml` konsumenta dodaj:

```yaml
depend:
  - VerdantVice
```

Jeśli integracja jest opcjonalna, użyj `softdepend` i obsłuż brak usługi.
VerdantVice nie publikuje w tym repozytorium artefaktu do zewnętrznego
repozytorium Maven. Podczas lokalnego rozwoju można skompilować przeciwko
produkcyjnemu JAR-owi:

```kotlin
dependencies {
    compileOnly(files("libs/VerdantVice-1.0.0.jar"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}
```

Konsument powinien generować bytecode Java 21, jeśli ma działać na obu
obsługiwanych liniach serwera.

## Metody API v1

| Metoda | Wynik | Charakter |
|---|---|---|
| `apiVersion()` | `int` | wersja kontraktu, obecnie 1 |
| `plugin()` | `Plugin` | właściciel usługi |
| `profile(UUID)` | `CompletableFuture<PlayerProfile>` | ładuje profil; domyślny fallback nowego profilu to `pl_PL` |
| `farm(UUID)` | `Optional<Farm>` | snapshot plantacji z pamięci |
| `playerFarm(UUID)` | `Optional<Farm>` | plantacja należąca do gracza |
| `plant(UUID)` | `Optional<Plant>` | snapshot rośliny z pamięci |
| `strain(String)` | `Optional<Strain>` | wyszukiwanie odmiany |
| `createContract(UUID, UUID)` | `CompletableFuture<StoredContract>` | generuje kontrakt gracz–zielarz |
| `marketQuote(String, double, double, double)` | `CompletableFuture<MarketQuote>` | notowanie dla odmiany, jakości, świeżości i reputacji |
| `currentPrice(String)` | `CompletableFuture<Double>` | cena referencyjna dla jakości 50, świeżości 100 i reputacji 0 |
| `registerStrain(Strain)` | `CompletableFuture<Void>` | dodaje odmianę do pamięci i inicjalizuje jej rekord rynku |

Wszystkie metody poza `plugin()` i `apiVersion()` wymagają gotowego
VerdantVice. Przy wywołaniu przed gotowością rzucany jest
`IllegalStateException` albo operacja kończy future wyjątkiem.

Obiekty domenowe zwracane przez API są niemutowalnymi snapshotami. Zmiana
lokalnej referencji nie aktualizuje bazy ani cache.

## Operacje asynchroniczne

Metody zwracające `CompletableFuture` mogą kończyć się poza głównym wątkiem.
Nie wolno w ich callbacku bezpośrednio modyfikować świata, encji,
ekwipunku ani GUI Bukkit. Przenieś takie działanie na scheduler pluginu
konsumenta:

```java
VerdantViceApi api = verdantViceApi().orElseThrow();

api.currentPrice("berry_fog").whenComplete((price, failure) -> {
    Bukkit.getScheduler().runTask(this, () -> {
        if (failure != null) {
            getLogger().warning("Nie udało się pobrać ceny: "
                    + failure.getMessage());
            return;
        }
        getLogger().info("Cena Berry Fog: " + price);
    });
});
```

Przekazywane zakresy jakości, świeżości i reputacji są walidowane przez
warstwę domenową. Nie zakładaj, że dowolne `double` zostanie automatycznie
przycięte.

## Zdarzenia Bukkit

Wszystkie zdarzenia znajdują się w
`com.verdantvice.api.event`.

| Zdarzenie | Dane | Anulowalne |
|---|---|---:|
| `VerdantPlantPlacedEvent` | gracz, ID rośliny, lokacja, odmiana | tak |
| `VerdantPlantStageChangeEvent` | ID rośliny, poprzedni i nowy etap | tak |
| `VerdantPlantDiseaseEvent` | ID rośliny, ID problemu, nasilenie | nie |
| `VerdantPlantHarvestEvent` | gracz, ID rośliny, ID partii, moment zbioru | tak |
| `VerdantBatchDryEvent` | ID partii, etap suszenia | nie |
| `VerdantBatchCureEvent` | ID partii, etap dojrzewania | nie |
| `VerdantProductUseEvent` | gracz, ID instancji przedmiotu, ID partii | tak |
| `VerdantHerbalistCreatedEvent` | encja zielarza | nie |
| `VerdantMarketPriceChangeEvent` | odmiana, poprzednia i nowa cena | tak |
| `VerdantContractCompleteEvent` | ID kontraktu, UUID właściciela | nie |
| `VerdantWorkerHiredEvent` | właściciel, encja workera, ID plantacji | tak |
| `VerdantWorkerTaskEvent` | ID workera, ID zadania, stan workera | nie |

Przykład:

```java
import com.verdantvice.api.event.VerdantPlantHarvestEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class HarvestListener implements Listener {
    @EventHandler(ignoreCancelled = true)
    public void onHarvest(VerdantPlantHarvestEvent event) {
        getLogger().info(event.player().getName()
                + " zebrał partię " + event.batchId()
                + " w oknie " + event.timing());
    }
}
```

Zarejestruj listener standardowo przez `PluginManager`. Dla zdarzeń
anulowalnych anulowanie jest decyzją ingerującą w rozgrywkę; rób to tylko z
jasnego powodu i nie wykonuj długich operacji bazodanowych w handlerze.

## Stabilność API

API v1 obejmuje wyłącznie interfejs `VerdantViceApi`, typy zwracane przez jego
sygnatury i wymienione zdarzenia. Klasy managerów, repozytoriów, GUI,
bezpieczeństwa i implementacja `DefaultVerdantViceApi` są detalami
wewnętrznymi.

Przed użyciem funkcji zależnej od nowej wersji sprawdź:

```java
if (api.apiVersion() < 1) {
    // wyłącz integrację
}
```

Nie przechowuj instancji usługi po wyłączeniu VerdantVice. Przy reloadzie
całego serwera pobierz ją ponownie.
