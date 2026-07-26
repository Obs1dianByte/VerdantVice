# Rozwiązywanie problemów

## Najpierw zbierz bezpieczne dane

Wykonaj:

```text
/vv admin database
/vv admin performance
/vv admin debug
```

Ostatnia komenda tworzy:

```text
plugins/VerdantVice/logs/diagnostic-<UTC>.yml
```

Raport zawiera wersje pluginu, serwera i Javy, stan i typ bazy, wielkość
kolejki, liczbę plantacji/roślin/graczy, język, debug i zadeklarowane moduły.
Nie odczytuje hasła bazy ani `signing.key`.

Do zgłoszenia dołącz także fragment konsoli od pierwszego błędu. Nie
publikuj `database.yml`, klucza, dumpu bazy ani danych graczy.

## Plugin nie uruchamia się

### `UnsupportedClassVersionError` albo zła Java

- Paper/Purpur 1.21.4: użyj co najmniej Javy 21;
- Paper/Purpur 26.2: użyj Javy 25;
- upewnij się, że proces serwera, a nie tylko terminal, używa właściwego
  `java`.

Sprawdź `java -version` w tym samym skrypcie startowym.

### Zainstalowano `-thin.jar`

Plik `VerdantVice-1.0.0-thin.jar` nie zawiera wymaganych bibliotek. Zastąp go
`VerdantVice-1.0.0.jar` z zadania `shadowJar`/`build` i usuń duplikaty.

### `Invalid configuration`

Sprawdź ostatnio zmieniony YAML, wcięcia i typ wartości. Najczęstsze
ograniczenia:

- `plants-per-cycle`: 1–10000;
- `max-plants-per-chunk`: 1–512;
- `database-save-seconds`: 5–86400;
- język z listy sześciu kodów;
- typ `SQLITE`, `MYSQL` lub `MARIADB`.

Jeżeli bezpieczny reload odrzuci konfigurację, aktywny snapshot pozostaje
niezmieniony. Napraw plik i ponów komendę.

### Brak lub niepełny język

`en_US.yml` jest obowiązkowy, a pozostałe języki muszą zawierać wszystkie
jego klucze tekstowe. Przywróć brakujące klucze z zasobów tego samego JAR-a;
nie usuwaj całego katalogu bez zachowania własnych tłumaczeń.

## Komendy odpowiadają „plugin nie jest gotowy”

Start DB i ładowanie danych są asynchroniczne. Poczekaj na:

```text
Plugin enabled successfully.
```

Jeśli komunikat nie pojawia się, wcześniejszy wyjątek wskazuje moduł, który
nie wystartował. Sprawdź połączenie DB, migracje i dostęp do plików.

## Problemy z bazą

### SQLite: `database is locked` / timeout

- nie otwieraj aktywnej bazy narzędziem zapisującym;
- upewnij się, że tylko jedna instancja serwera używa pliku;
- nie kopiuj na żywo samego `.db`;
- zachowaj `-wal` i `-shm` podczas diagnozy, ale do odtworzenia używaj
  spójnego pliku z backupu;
- rozważ zwiększenie `sqlite.busy-timeout-millis`, potem restart.

### MySQL/MariaDB: brak połączenia

Sprawdź host, port, nazwę bazy, konto, hasło, firewall, TLS i prawa do
tworzenia/modyfikowania tabel. `database.yml` nie przeładowuje się na żywo —
wykonaj pełny restart.

`retry.attempts` dotyczy przejściowych operacji, ale nie naprawi złych danych
logowania.

### Kolejka DB rośnie

Sprawdź `/vv admin database`. Dla baz sieciowych można ostrożnie dostosować
`pool.maximum-size`, `executor.threads` i `executor.queue-capacity`. SQLite
celowo używa jednego workera, więc najpierw zmniejsz obciążenie:

- `plants-per-cycle`;
- liczbę roślin/workerów;
- częstość zapisów i aktualizacji.

Nie zwiększaj executorów SQLite — kod i tak wymusza jeden.

### Błąd checksumy, luka lub `success = 0`

Nie edytuj `vv_schema_history`. Odtwórz spójny backup lub zainstaluj JAR
zgodny z wersją bazy. Procedura: [MIGRATION.md](MIGRATION.md).

## Przedmiot jest odrzucany jako nieprawidłowy/duplikat

VerdantVice sprawdza jednocześnie podpis PDC i stan instancji w
`item_registry`. Przedmiot zostanie odrzucony po modyfikacji, skopiowaniu,
ponownym użyciu lub odtworzeniu bazy z innego punktu niż ekwipunki graczy.

Sprawdź:

1. czy nie zmienił się `data/signing.key`;
2. czy baza i klucz pochodzą z tego samego backupu;
3. czy nie działa plugin modyfikujący PDC/`CustomModelData`;
4. czy gracz nie próbuje użyć wejścia już zużytego przez transakcję.

Nie regeneruj klucza jako „naprawy”. To unieważni wszystkie pozostałe
przedmioty.

## Roślina nie rośnie

Sprawdź `/vv admin inspect`:

- etap, zdrowie, nawodnienie, odżywienie i stres;
- minimalne zdrowie wzrostu;
- problem `GROWTH_LOCK`;
- wartości światła i czystości;
- `performance.offline-growth` i `unloaded-chunk-growth`;
- czy scheduler nie ma błędu w logu.

Aktualizacja odbywa się porcjami, więc przy wielu roślinach wynik może nie
pojawić się natychmiast.

## Nie można posadzić lub utworzyć plantacji

- nasiono, donica i różdżka muszą być podpisane;
- stanowisko musi zachować minimalny odstęp;
- obowiązuje limit gracza i chunka;
- akcja musi przejść prawa roli plantacji;
- aktywny WorldGuard/GriefPrevention/Lands musi zezwolić;
- przy błędzie adaptera domyślny `DENY_IF_UNKNOWN` blokuje akcję.

Sprawdź log ostrzeżenia adaptera. Tylko po świadomej ocenie ryzyka zmień
fallback na tryb inny niż `DENY_IF_UNKNOWN`.

## Stacja przetwarzania jest zajęta

Nie można rozbić stacji z aktywną partią. Poczekaj na zakończenie i odbierz
wynik właścicielem. Stan joba jest w DB, a pozycja stacji w
`data/processing-stations.yml`; oba muszą pochodzić z tego samego backupu.

Po awarii minigra powinna oddać wejścia przez kontrolowaną ścieżkę. Jeśli
operacja ma niejednoznaczny commit DB, nie wydawaj ręcznie duplikatu przed
sprawdzeniem rejestru i logu.

## Worker stoi lub nie wykonuje zadania

Sprawdź:

- harmonogram czasu świata w `workers.yml`;
- drogę, drzwi, wysokość i załadowany chunk;
- `path-retry-count` oraz `path-timeout-seconds`;
- rezerwację celu i limit roślin na workera;
- morale, zaległe pensje, łóżko, jedzenie i przeciążenie;
- prawa `MANAGE_WORKERS` oraz limit workerów plantacji.

Awaryjny teleport jest domyślnie wyłączony. Włączenie
`emergency-worker-teleport` powinno być ostatnim zabezpieczeniem, nie
zamiennikiem poprawnej nawigacji.

## Zielarz się nie pojawia

Tylko villager z profesją `FARMER` przechodzi jednorazową ewaluację.
Obowiązuje `conversion-chance`, limit w promieniu wioski i zapisany marker
ewaluacji. Zmiana szansy nie losuje ponownie już ocenionych villagerów.
Do kontrolowanego testu użyj komendy administracyjnej.

`/vv admin spawnherbalist [poziom]` nadal respektuje limit wioski. Jeśli nie
patrzysz na villagera, komenda tworzy nowego farmera nad wskazanym blokiem.

## Nie można zatrudnić workera

Samo `verdantvice.admin` nie omija warunków domenowych. `/vv admin
hireworker` wymaga jednocześnie:

- celowanego do 8 bloków, zarejestrowanego zielarza
  `VERDANT_EXPERT`;
- poprawnie podpisanego `PLANTATION_CONTRACT` w głównej ręce;
- plantacji gracza, którą może zarządzać;
- wolnego miejsca poniżej limitu workerów;
- zielarza, który nie jest już zatrudniony.

Nie wydawaj drugiego kontraktu po niejednoznacznym błędzie DB bez sprawdzenia
rejestru przedmiotów.

## Vault nie działa

Samo Vault nie jest providerem ekonomii. Zainstaluj również provider, ustaw
`provider: VAULT` lub `vault.enabled: true`, wykonaj `/vv admin reload` i
sprawdź w logu wiersz `Economy: Vault.`. Przy braku usługi VerdantVice
bezpiecznie pozostaje przy szmaragdach.

## PlaceholderAPI zwraca pustą wartość

PlaceholderAPI musi być aktywne podczas finalizacji startu VerdantVice.
Identyfikator to `verdantvice`. Dostępne placeholdery:

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

Nieznany parametr zwraca `null`, a brak gracza pusty tekst.

## Paczka zasobów nie działa

- wybierz właściwy wariant 1.21.4 albo 26.2;
- `pack.mcmeta` musi być w korzeniu ZIP-a;
- URL musi prowadzić bezpośrednio do ZIP-a przez HTTPS;
- SHA-1 ma dokładnie 40 znaków hex i odpowiada bieżącemu plikowi;
- po zmianie pliku wyczyść cache klienta albo zmień URL;
- sprawdź `enabled` oraz `send-on-join`.

Bez paczki przedmioty mają wyglądać jak poprawne materiały vanilla. Pełna
instrukcja: [RESOURCE_PACK.md](RESOURCE_PACK.md).

## API jest `null`

To może być prawidłowe w `onEnable()` pluginu zależnego, ponieważ VerdantVice
rejestruje usługę dopiero po załadowaniu DB i cache. Pobieraj ją leniwie z
`ServicesManager` i ponów próbę po komunikacie gotowości. Nie rzutuj obiektu
pluginu. Zobacz [API.md](API.md).

## `runServer` nie startuje

- użyj JDK 25;
- zapewnij dostęp do repozytoriów Gradle i Paper;
- przeczytaj EULA i w razie wymagania ustaw `eula=true` w `run/eula.txt`;
- usuń tylko uszkodzone pliki testowego `run/`, nie katalog produkcyjny;
- nie próbuj tym zadaniem uruchomić 1.21.4 — jest skonfigurowane na 26.2.
