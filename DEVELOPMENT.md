# Rozwój VerdantVice

## Architektura

Projekt jest wielomodułowym buildem Gradle:

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

`verdantvice-core` zawiera niemutowalny model domenowy i usługi obliczeniowe
bez zależności od Bukkit: rośliny, genetykę, przetwarzanie, efekty,
tolerancję, rynek, kontrakty, plantacje i workerów.

`verdantvice-paper` jest adapterem wykonawczym: bootstrap pluginu, listenery,
GUI, encje i display entities, PDC, komendy, repozytoria SQL, integracje,
lokalizacja oraz publiczne API.

## Narzędzia i wersje

- Java source/target/release: 21;
- Gradle wrapper: 9.6.1;
- Paper API do kompilacji: 1.21.4;
- JUnit Jupiter: 5.13.4;
- Shadow: 9.6.1;
- run-paper: 3.0.2;
- HikariCP: 6.3.3;
- SQLite JDBC: 3.50.3.0;
- MySQL Connector/J: 9.4.0;
- MariaDB Java Client: 3.5.6;
- Gson: 2.13.2.

Zależności wykonawcze są cieniowane do produkcyjnego JAR-a; Hikari i Gson
mają relokowane pakiety. Paper i PlaceholderAPI pozostają `compileOnly`.

## Zasady warstwy domenowej

- Modele są niemutowalne. Operacja zwraca nowy obiekt, który wywołujący musi
  zapisać.
- Czas przekazuj jawnie jako `Instant`; testy nie powinny zależeć od zegara
  systemowego.
- Losowość przyjmuj jako parametr lub izoluj w adapterze, aby test był
  deterministyczny.
- Waliduj zakresy i UUID na granicy modelu.
- Kod Core nie importuje klas Bukkit/Paper ani SQL.
- Zmiana stanu partii unieważnia poprzednią sygnaturę i wymaga ponownego
  podpisania przez warstwę uprawnioną do zapisu.

## Wątki i serwer

Świat, encje, inventory i GUI wolno modyfikować wyłącznie na głównym wątku.
Repozytoria zwracają `CompletableFuture` i wykonują SQL na ograniczonym
executorze. Po zakończeniu operacji DB adapter wraca przez scheduler do
Bukkit.

Nie używaj `join()` ani `get()` na głównym wątku. Nie uruchamiaj zapytania
SQL na ticku serwera. Operacje ekonomii, również Vault, są celowo wykonywane
na głównym wątku.

Aktualizacje roślin i workerów muszą pozostać porcjowane limitami z
konfiguracji. Nie iteruj po wszystkich rekordach świata w jednym ticku.

## Trwałość i idempotencja

Repozytoria używają `row_version`, transakcji, rezerwacji i porównań CAS.
`item_registry` jest źródłem prawdy o stanie instancji przedmiotu, a
`operation_journal` zabezpiecza powtórzenie operacji. Nowa ścieżka handlu lub
przetwarzania powinna:

1. zweryfikować podpis PDC;
2. zarezerwować instancję w bazie;
3. wykonać mutację atomowo/idempotentnie;
4. oznaczyć wejście jako zużyte;
5. zarejestrować i wydać wyjście;
6. bezpiecznie odtworzyć wejście przy jednoznacznym rollbacku.

Nigdy nie twórz stanowego produktu zwykłym `new ItemStack` bez
`SecureItemService`.

## Migracje bazy

Migracje znajdują się w `MigrationCatalog` i mają kolejne wersje bez luk.
Każda ma SHA-256 zależny od numeru, opisu i SQL.

- Nigdy nie edytuj już wydanej migracji.
- Dodaj nową wersję na końcu katalogu.
- Przygotuj warianty SQL dla SQLite, MySQL i MariaDB.
- Używaj `utf8mb4` i InnoDB dla baz sieciowych.
- Dodaj test czystej migracji oraz aktualizacji ze starej wersji.
- Nie ukrywaj częściowo zastosowanej migracji; start powinien zakończyć się
  bezpiecznym błędem.

Aktualny schemat ma wersję 3. Więcej: [MIGRATION.md](MIGRATION.md).

## Konfiguracja

Nowy plik konfiguracyjny należy:

1. dodać do zasobów;
2. dodać do `ConfigManager.CONFIG_FILES`;
3. skopiować tylko, jeśli nie istnieje;
4. zwalidować przed podmianą aktywnego snapshotu;
5. opisać w [CONFIGURATION.md](CONFIGURATION.md).

Jeśli zmiana nie może być zastosowana bezpiecznie na żywo, dodaj plik do
listy wymagającej restartu. Nie przechowuj haseł ani sekretów w diagnostyce.

## Lokalizacja

`en_US.yml` jest kanonicznym zestawem kluczy. Każdy z pięciu pozostałych
języków musi zawierać wszystkie jego wartości tekstowe. Po dodaniu klucza
zaktualizuj wszystkie sześć plików.

Wiadomości używają MiniMessage. Dane użytkownika przekazuj jako
`Placeholder.unparsed`, nie sklejaj ich z szablonem. Zachowaj nazwy
placeholderów pomiędzy tłumaczeniami.

## Publiczne API

Nie wystawiaj repozytoriów ani mutowalnych cache. Nowe API powinno:

- zwracać snapshot lub `CompletableFuture`;
- jawnie dokumentować wątek;
- zachować zgodność `API_VERSION`;
- emitować zdarzenie dla istotnej mutacji;
- nie ujawniać podpisu, sekretu ani danych połączenia.

Opis kontraktu v1: [API.md](API.md).

## Resource pack

Numery `CustomModelData` w `items.yml`, definicje
`assets/minecraft/items/*.json` i modele w obu wariantach muszą pozostać
zgodne. Zmiana atlasu powinna regenerować jednocześnie 1.21.4 i 26.2.
Szczegóły: [RESOURCE_PACK.md](RESOURCE_PACK.md).

## Lista kontrolna zmiany

1. Dodaj lub popraw test domenowy.
2. Dla SQL przetestuj wszystkie trzy dialekty albo wyraźnie ogranicz zmianę.
3. Uruchom `clean build`.
4. Sprawdź brak blokowania głównego wątku.
5. Sprawdź restart podczas aktywnego suszenia, kontraktu i zadania workera.
6. Sprawdź scenariusz powtórzonego kliknięcia/pakietu.
7. Uzupełnij sześć języków i dokumentację.
8. Uruchom ręcznie Paper 1.21.4/Java 21 oraz Paper 26.2/Java 25.
9. Zweryfikuj zawartość produkcyjnego JAR-a, nie tylko `-thin.jar`.

Polecenia i raporty testów opisuje [TESTING.md](TESTING.md).
