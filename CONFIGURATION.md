# Konfiguracja VerdantVice

## Położenie i format

Wszystkie pliki są kopiowane przy pierwszym starcie do
`plugins/VerdantVice/`. Plugin ładuje 17 plików YAML oraz sześć plików
językowych z `plugins/VerdantVice/languages/`. Pliki źródłowe znajdują się w
`verdantvice-paper/src/main/resources/`.

Używaj UTF-8, spacji zamiast tabulatorów i nie zmieniaj wcięć list lub map.
Wartości czasu mają jednostkę podaną w nazwie klucza (`-ticks`, `-seconds`,
`-minutes`, `-hours`, `-millis`).

## Przeładowanie

```text
/vv admin reload
```

Polecenie atomowo wczytuje i waliduje wszystkie bezpieczne pliki, ponownie
ładuje języki i katalog odmian oraz ponawia wykrycie ochrony regionów i
ekonomii. `database.yml` jest celowo pomijany i pojawia się w raporcie jako
wymagający restartu.

Pełnego restartu używaj zawsze po zmianie:

- typu bazy, adresu, danych logowania, puli lub kolejki DB;
- JAR-a pluginu;
- klucza podpisu (normalnie nie należy go zmieniać);
- ustawień, których efekt inicjalizuje scheduler, jeśli zmiana nie jest
  widoczna po bezpiecznym reloadzie.

Nigdy nie używaj serwerowego `/reload`.

## Walidacja startowa

Plugin odrzuca konfigurację, jeśli:

- `performance.plants-per-cycle` nie mieści się w `1..10000`;
- `performance.max-plants-per-chunk` nie mieści się w `1..512`;
- `performance.database-save-seconds` nie mieści się w `5..86400`;
- język domyślny nie jest jednym z sześciu obsługiwanych;
- typ bazy nie jest `SQLITE`, `MYSQL` albo `MARIADB`;
- ścieżka SQLite lub backupów wychodzi poza katalog VerdantVice;
- parametry puli, retry lub kolejki bazy są poza zakresem kodu;
- w wartości tekstowej YAML znajduje się znak NUL.

W razie błędu plugin nie przechodzi do stanu gotowości.

## Mapa plików

| Plik | Odpowiedzialność |
|---|---|
| `config.yml` | język, limity, wydajność, bezpieczeństwo, deklaracje modułów |
| `database.yml` | SQLite/MySQL/MariaDB, pula, retry i kopie |
| `plants.yml` | tempo zmian stanu, pielęgnacja, problemy, materiały etapów |
| `strains.yml` | katalog 12 odmian i ich parametry |
| `genetics.yml` | katalog progów i ustawień genetyki |
| `quality.yml` | wagi jakości oraz suszenie, dojrzewanie, mielenie, mieszanie |
| `effects.yml` | czas, siła, tolerancja, ryzyko i bezpieczne limity efektów |
| `villagers.yml` | konwersja zielarzy, poziomy, oferty i kontrakt zatrudnienia |
| `workers.yml` | AI, harmonogram, zadania, pensje i morale |
| `economy.yml` | szmaragdy/Vault i ograniczenia cen |
| `market.yml` | aktualizacje, podaż, popyt, historia i wydarzenia |
| `contracts.yml` | generowanie, terminy, nagrody i limity kontraktów |
| `gui.yml` | rozmiary, sloty, nawigacja i parametry minigry |
| `items.yml` | materiały vanilla i `CustomModelData` |
| `integrations.yml` | opcjonalne integracje i polityka awarii ochrony |
| `resourcepack.yml` | wysyłanie hostowanej paczki zasobów |
| `notifications.yml` | typy, kanały i dźwięki powiadomień |

## `config.yml`

### Język

```yaml
default-language: pl_PL
auto-detect-client-language: true
debug: false
```

Przy włączonym automatycznym wykrywaniu używany jest obsługiwany język
klienta. Ręczny wybór `/vv language` ma pierwszeństwo i jest zapisywany w
bazie. Dla konsoli obowiązuje język domyślny.

### Limity

- `limits.plants-per-player` — domyślnie 64;
- `limits.farms-per-player` — domyślnie 2;
- `limits.minimum-station-distance` — minimalna odległość stanowisk,
  domyślnie 2,0.

Administrator z `verdantvice.admin` omija limit roślin i plantacji, ale nie
wszystkie pozostałe reguły integralności.

### Wydajność

- `plant-update-seconds` i `plants-per-cycle` sterują porcjowaniem wzrostu;
- `worker-update-ticks` steruje taktem maszyny stanów pracowników;
- `database-save-seconds` określa okres zbiorczego zapisu profili;
- `offline-growth` i `unloaded-chunk-growth` zezwalają na obliczenie upływu
  czasu bez aktywnego gracza/chunka;
- `max-plants-per-chunk`, `max-workers-per-farm` i
  `max-displays-per-chunk` są twardymi limitami ochronnymi;
- `emergency-worker-teleport` i `emergency-worker-teleport-seconds`
  kontrolują opcjonalne awaryjne odblokowanie workera.

### Bezpieczeństwo i moduły

Przedmioty są w tej wersji zawsze podpisywane i sprawdzane przez rejestr DB.
Wpisy `security.require-signed-items`, `reservation-timeout-seconds` i
`interaction-cooldown-millis` są załadowanymi ustawieniami katalogowymi, ale
bieżące ścieżki bezpieczeństwa nie przełączają się na tryb bez podpisu.

Sekcja `modules.*` jest raportowana w diagnostyce. Bieżący bootstrap tworzy
wszystkie podsystemy niezależnie od tych flag; do funkcjonalnego wyłączenia
efektów lub kontraktów służą ich własne klucze `enabled`.

## `database.yml`

Domyślny wariant:

```yaml
type: SQLITE
sqlite:
  file: data/verdantvice.db
  backup-on-start: true
  backup-interval-hours: 12
```

Kod rozpoznaje również opcjonalne klucze:

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

SQLite wymusza jedną kolejkę niezależnie od `executor.threads`, używa WAL,
`synchronous=NORMAL`, kluczy obcych i `busy_timeout`. Kopia startowa jest
tworzona tylko wtedy, gdy istnieje niepusta baza. Kopie cykliczne można
wyłączyć wartością `backup-interval-hours: 0`.

MySQL/MariaDB używają:

- `host`, `port`, `database`, `username`, `password`;
- `parameters` w formacie `klucz=wartość&klucz2=wartość`;
- puli `minimum-idle`, `maximum-size`, timeoutu połączenia i maksymalnego
  czasu życia.

Wewnętrzne kopie są dostępne wyłącznie dla SQLite. Szczegóły:
[MIGRATION.md](MIGRATION.md).

## `plants.yml`

Sekcja `growth` określa godzinowe straty zdrowia/nawodnienia/odżywienia,
regenerację stresu, minimalne zdrowie wzrostu, optymalny zakres nawodnienia
oraz czas przejrzenia i zepsucia.

Sekcja `care` definiuje siłę konewki, odżywki, zestawu czyszczącego, oprysku i
naprawy. `problems` ustala szansę problemu na aktualizację, minimalny odstęp i
listę dopuszczonych problemów. `display.material-by-stage` mapuje osiem
etapów na materiały vanilla.

## `strains.yml`

Każda pozycja w `strains` zawiera:

- nazwę i `rarity`;
- czas wzrostu, trudność, plon i potencjał jakości;
- odporność, zużycie wody, preferencję światła, wrażliwość na stres i zapach;
- dodatni i ujemny profil efektów;
- cenę nasiona i bazową cenę rynku;
- identyfikator modelu i profilu cząsteczek.

Wbudowane identyfikatory:

```text
berry_fog, concrete_kush, lemon_static, green_alley,
neon_haze, night_widow, purple_crash, velvet_smoke,
ghost_garden, royal_static, black_orchard, verdant_vice
```

Identyfikator jest częścią trwałych danych. Nie zmieniaj go po rozpoczęciu
produkcji. Zmianę nazwy wyświetlanej wykonuj przez `display-name`.

## `genetics.yml`

Plik zawiera szansę mutacji, karę stabilności krzyżówki, ograniczenia nazwy
i progi klas od `DAMAGED` do `LEGENDARY`. Jest ładowany oraz walidowany jak
pozostałe YAML-e, lecz bieżące usługi genetyki domenowej korzystają z
polityk konstruowanych w kodzie; traktuj te wpisy jako katalog balansu do
dalszej integracji, a nie jako gwarantowany przełącznik każdego obliczenia.

## `quality.yml`

`weights` składają wynik 0–100 z genetyki, opieki, zdrowia, czasu zbioru,
czystości, suszenia, dojrzewania i umiejętności. `penalties` opisuje
zanieczyszczenie i zepsucie. `grades` zawiera czytelne progi katalogowe.

Sekcja `processing` jest używana przy:

- aktualizacji pracy poza załadowanym chunkem;
- okresie aktualizacji;
- czasie, pojemności, czystości, stabilności i prędkości suszenia;
- czasie, pojemności, czystości i stabilności dojrzewania;
- wydajności i ryzyku mielenia;
- maksymalnej liczbie partii mieszanki;
- czasie rundy i zielonym oknie pięcioetapowej minigry.

Enum domenowy klasyfikuje wynik według własnych progów. Zmiana samej mapy
`grades` nie zmienia automatycznie wartości `QualityGrade.fromScore`.

## `effects.yml`

`enabled` wyłącza używanie produktów efektowych. Pozostałe sekcje ograniczają
czas, wzmacniacz, ryzyko efektów ujemnych, blokadę silnego efektu oraz przyrost
i rzeczywisty czas zaniku tolerancji. `safe-limits.minimum-health` zapobiega
zastosowaniu efektu przy zbyt małym zdrowiu.

## `villagers.yml`

- `herbalist.conversion-chance` jest jednorazowym losowaniem farmera;
- `max-per-village` obowiązuje w promieniu `village-radius`;
- `daily-buy-limit` ogranicza sprzedaż;
- progi `levels` wyznaczają poziomy zielarza;
- `hiring-contract` określa koszt kontraktu zatrudnienia w szmaragdach,
  książkach i blokach złota.

Kod rozpoznaje także opcjonalne `offer-prices.<id>` i
`offer-prices.book-<id>`.

## `workers.yml`

Plik steruje liczbą roślin na pracownika, czasem rezerwacji, próbami i
timeoutem ścieżki, harmonogramem dnia, pensją, progami zaległości, morale i
priorytetami typów zadań. Czas harmonogramu jest czasem świata Minecraft
`0..23999`.

Nie ustawiaj `task-reservation-seconds` krócej niż realny czas dojścia do
najdalszego celu. Awaryjny teleport jest osobnym ustawieniem w `config.yml`.

## `economy.yml`

`provider: EMERALD` nie wymaga zależności. Wypłata przepełniająca ekwipunek
upuszcza bezpiecznie stosy przy graczu. Dla Vault ustaw `provider: VAULT` lub
`vault.enabled: true` i zainstaluj Vault oraz provider ekonomii.

Mnożniki kupna/sprzedaży oraz minimalna/maksymalna cena są używane przy
ofertach, kontraktach i transakcjach.

## `market.yml` i `contracts.yml`

Rynek aktualizuje się domyślnie co 30 minut. Ceny są ograniczone przez
minimalny i maksymalny mnożnik; wpływają na nie podaż, popyt, powrót do ceny
bazowej, powtarzalna sprzedaż i losowe wydarzenia.

Kontrakty można wyłączyć przez `enabled: false`. Plik określa dopuszczone
typy, minimalny/maksymalny termin, mnożnik i reputację nagrody oraz limit
aktywnych kontraktów.


### Akceptowane stany sprzedaży

`market.yml` zawiera `trade.accepted-item-types`. Domyślnie zielarz przyjmuje
`DRY_BATCH`, `CURED_BATCH` i `PACKAGE`. `FRESH_BATCH` można dodać świadomie;
wtedy cena korzysta z prawdziwej `freshWeight` i mnożnika
`trade.fresh-price-multiplier`. Podpis, rejestr itemów, właściciel, rekord
`ProductBatch` i stan partii są nadal sprawdzane przed utworzeniem oferty.

## `gui.yml`

Rozmiar GUI musi być poprawnym rozmiarem ekwipunku; kod normalizuje go do
wielokrotności 9. Sloty muszą mieścić się w danym GUI. `page-size` ogranicza
liczbę wpisów strony. Minigra korzysta z ustawień `rolling` w tym pliku oraz
szczegółowych wartości `processing.rolling` w `quality.yml`.

## `items.yml`

Materiały są fallbackiem vanilla, a `custom-model-data` wiąże przedmiot z
paczką zasobów. Wbudowany zakres to `12001..12017`. Zmiana materiału lub
numeru wymaga równoległej zmiany definicji w obu wariantach paczki. Istniejące
podpisane przedmioty zachowują zapisane dane PDC.

## `integrations.yml`

Wykonywalne adaptery tej wersji:

- `vault`;
- `placeholderapi`;
- `worldguard`;
- `griefprevention`;
- `lands`.

`region-protection-fallback: DENY_IF_UNKNOWN` jest trybem fail-closed. Każda
inna wartość nie blokuje wyłącznie z powodu odpowiedzi `UNKNOWN`, lecz jawna
odmowa integracji nadal blokuje akcję.

Flagi `itemsadder`, `oraxen` i `luckperms` są zarezerwowane i nie uruchamiają
osobnego adaptera w tej wersji.

## `resourcepack.yml`

Paczka jest wysyłana tylko, gdy `enabled: true`, URL nie jest pusty, a `sha1`
ma dokładnie 40 cyfr szesnastkowych. `required` jest przekazywane do Paper,
`send-on-join` wysyła paczkę po wejściu, a `prompt-key` wskazuje klucz
MiniMessage.

`fallback-without-pack` jest obecnie wpisem informacyjnym; vanilla fallback
wynika z materiałów przedmiotów niezależnie od tej flagi.

## `notifications.yml`

Obsługiwane typy:

```text
PLANT_READY, DISEASE, LOW_WATER, DRYING_COMPLETE, CURING_COMPLETE,
UNPAID_WORKER, BLOCKED_WORKER, CONTRACT_COMPLETE, MARKET_CHANGE
```

Kanały konfigurowane w pliku to m.in. `CHAT`, `ACTION_BAR`, `TITLE` i
`BOSS_BAR`. Gracz może włączyć lub wyłączyć konkretny typ poleceniem.
Nazwy dźwięków muszą odpowiadać enumom `Sound` bieżącego Paper.

## Języki

Pliki w `languages/` używają MiniMessage. Łańcuch fallbacku to:

1. język gracza;
2. `default-language`;
3. `en_US`;
4. czerwony znacznik brakującego klucza.


Język gracza steruje GUI, książkami i wiadomościami. `main.default-language`
steruje globalnymi nazwami encji, takimi jak nazwa zielarza. Bukkitowe
`customName` jest wspólne dla wszystkich graczy, dlatego nie jest lokalizowane
osobno dla każdego obserwatora. Bezpieczny reload odświeża nazwy już
załadowanych, oznaczonych zielarzy.

`en_US.yml` jest wymaganym wzorcem. Każdy inny język musi zawierać wszystkie
jego klucze tekstowe, inaczej start/reload zakończy się błędem. Placeholdery
takie jak `<player>` są wstawiane jako tekst nieinterpretowany, ale znaczniki
formatowania MiniMessage w samym szablonie są aktywne.

## Klucz podpisu

`data/signing.key` jest generowany losowo (256 bitów), zapisywany atomowo i
w miarę możliwości ograniczany prawami systemu plików do właściciela. Nie
edytuj, nie regeneruj i nie udostępniaj go. Kopia klucza musi odpowiadać
kopii bazy; mieszanie klucza z innym stanem powoduje odrzucanie przedmiotów.
