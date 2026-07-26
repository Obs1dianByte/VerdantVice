# Migracje, aktualizacje i kopie danych

## Model migracji

VerdantVice wykonuje migracje automatycznie podczas asynchronicznego startu,
zanim zarejestruje publiczne API i ogłosi gotowość. Historia znajduje się w
tabeli:

```text
vv_schema_history
```

Każdy wpis przechowuje wersję, opis, SHA-256 SQL, czas instalacji, czas
wykonania i flagę sukcesu. Katalog wersji jest ciągły.

Aktualny schemat:

| Wersja | Zakres |
|---:|---|
| 1 | gracze, umiejętności, tolerancja, plantacje, członkowie, magazyny, genetyka, odmiany, rośliny i ich zdarzenia |
| 2 | partie, suszenie, dojrzewanie, zielarze, workerzy, zadania, pensje, kontrakty, handel i rynek |
| 3 | atomowy `item_registry` i idempotentny `operation_journal` |

Już wydanej migracji nie wolno edytować. Zmiana checksumy, luka, nieznana
wersja lub wpis `success = 0` zatrzymuje start zamiast ryzykować uszkodzenie
danych.

## Co należy kopiować

Pełny stan składa się z:

- bazy SQLite albo bazy MySQL/MariaDB;
- `plugins/VerdantVice/data/signing.key`;
- `plugins/VerdantVice/data/processing-stations.yml`;
- plików konfiguracyjnych i własnych tłumaczeń;
- opcjonalnie logów diagnostycznych.

Sam dump SQL nie wystarcza do pełnego odtworzenia. `signing.key` musi być
zgodny z bazą, ponieważ podpisuje tożsamość przedmiotów i partii.
`processing-stations.yml` przechowuje pozycje fizycznych stacji, podczas gdy
same partie i joby znajdują się w SQL.

## Kopie SQLite

Przy domyślnym `backup-on-start: true` plugin:

1. sprawdza, czy istnieje niepusty plik bazy;
2. otwiera bazę z WAL;
3. wykonuje checkpoint pasywny;
4. tworzy spójną kopię przez SQLite `VACUUM INTO`;
5. atomowo przenosi plik `.partial` do katalogu backupów;
6. usuwa kopie starsze niż limit retencji.

Nazwy wyglądają podobnie do:

```text
verdantvice-20260727-120000-000-startup.db
verdantvice-20260727-240000-000-manual.db
```

Kopie cykliczne mają domyślnie odstęp 12 godzin. Kod rozpoznaje
`sqlite.max-backups` z wartością domyślną 14. Wbudowane kopie nie obejmują
`signing.key`, stacji ani YAML-i — archiwizuj cały katalog osobno.

## Kopie MySQL/MariaDB

Plugin nie wykonuje dumpów baz sieciowych. Użyj narzędzi i polityki backupu
administratora bazy. Dump powinien być transakcyjnie spójny i zachowywać
kodowanie `utf8mb4`, indeksy, klucze obce oraz tabelę historii.

Przed aktualizacją zatrzymaj serwer albo zapewnij spójny snapshot bazy i
pliku klucza. Nie uruchamiaj równolegle dwóch instancji wykonujących migracje
na tej samej bazie.

## Bezpieczna aktualizacja JAR-a

1. Przeczytaj uwagi do wersji i wymagania Javy/Paper.
2. Wykonaj `clean build` i zachowaj produkcyjny JAR.
3. Zatrzymaj serwer.
4. Wykonaj pełną kopię opisaną wyżej.
5. Zamień tylko JAR VerdantVice; usuń stare i `-thin.jar`.
6. Nie kopiuj domyślnych YAML-i na istniejące pliki w ciemno.
7. Uruchom serwer.
8. W logu sprawdź każdą `Applying database migration ...`, wynik schematu i
   `Plugin enabled successfully.`.
9. Wykonaj `/vv admin database` i test odczytu istniejącej rośliny, partii,
   plantacji, kontraktu i workera.
10. Zachowaj backup do końca okresu obserwacji.

`/vv admin migrate` wywołuje bieżący manager bazy, który pamięta future
inicjalizacji. Nie służy do zastosowania migracji z nowego JAR-a bez
restartu. Migracja wersji pluginu zawsze odbywa się podczas pełnego startu.

## Odtworzenie SQLite

1. Zatrzymaj serwer i potwierdź brak procesu Java korzystającego z bazy.
2. Zachowaj uszkodzony katalog do analizy.
3. Wybierz kopię odpowiadającą kopii `signing.key` i stacji.
4. Zastąp `data/verdantvice.db` wybraną kopią.
5. Nie przenoś starych plików `verdantvice.db-wal` ani
   `verdantvice.db-shm`.
6. Przywróć odpowiadające `signing.key` i `processing-stations.yml`.
7. Uruchom serwer i sprawdź historię/migracje.
8. Wykonaj scenariusz integralności przed ponownym wpuszczeniem graczy.

Nie usuwaj ręcznie wpisu `success = 0`. Nie ma gwarancji, które instrukcje
DDL zostały wykonane przez dany silnik przed rollbackiem.

## Odtworzenie MySQL/MariaDB

1. Zatrzymaj serwer.
2. Przywróć dump do pustej bazy lub w kontrolowany sposób zastąp całą bazę.
3. Przywróć klucz i stacje z tego samego punktu w czasie.
4. Zweryfikuj konto, TLS/parametry i prawa do tabel.
5. Uruchom VerdantVice i sprawdź `vv_schema_history`.

Jeśli baza zawiera wersję nowszą niż katalog w JAR-ze, używasz zbyt starego
pluginu. Zainstaluj zgodną lub nowszą wersję; nie obniżaj ręcznie numeru.

## Zmiana silnika bazy

Zmiana `type` nie kopiuje danych. VerdantVice nie zawiera automatycznego
konwertera SQLite ↔ MySQL ↔ MariaDB. Do migracji między silnikami potrzebny
jest kontrolowany eksport/import zachowujący:

- tekstowe UUID;
- milisekundy epoch w polach czasu;
- JSON;
- wszystkie constraints i indeksy;
- `row_version`;
- historię schematu;
- rejestr przedmiotów i dziennik operacji.

Przetestuj konwersję offline na kopii i nie uruchamiaj starego oraz nowego
silnika równocześnie.

## Downgrade

Migracje są jednokierunkowe. Starszy JAR odrzuci nieznaną wersję schematu.
Jedyną bezpieczną ścieżką downgrade jest odtworzenie kompletnego backupu
wykonanego przed aktualizacją, wraz z kluczem, stacjami i właściwym JAR-em.
