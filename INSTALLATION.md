# Instalacja VerdantVice

## 1. Dobór środowiska

Obsługiwane warianty:

- Paper/Purpur 1.21.4 na Javie 21;
- Paper/Purpur 26.2 na Javie 25.

Ten sam produkcyjny JAR działa w obu wariantach. Plugin nie deklaruje obsługi
Folia, Spigot ani CraftBukkit. Przed instalacją wykonaj pełną kopię świata,
katalogu `plugins/` i bazy serwera.

## 2. Instalacja podstawowa z SQLite

1. Zatrzymaj serwer.
2. Skopiuj `VerdantVice-1.0.0.jar` do `plugins/`.
3. Usuń ewentualny `VerdantVice-1.0.0-thin.jar`; nie powinien znajdować się
   na serwerze.
4. Uruchom serwer.
5. Obserwuj konsolę aż do komunikatu:

   ```text
   Plugin enabled successfully.
   ```

6. Wykonaj `/vv admin database`. Oczekiwany typ to `SQLITE`, a stan bazy
   powinien przyjmować pracę.
7. Wejdź jako gracz i sprawdź `/vv guide`.

Przy pierwszym starcie powstają:

```text
plugins/VerdantVice/
├── *.yml
├── languages/
│   ├── pl_PL.yml
│   └── ...
├── data/
│   ├── verdantvice.db
│   ├── signing.key
│   └── processing-stations.yml
├── backups/
└── logs/
```

`processing-stations.yml` powstaje dopiero po zapisaniu stacji. Baza SQLite
może mieć podczas pracy pliki `-wal` i `-shm`; nie kopiuj samego pliku `.db`
na żywo bez bezpiecznego mechanizmu backupu.

## 3. Instalacja z MySQL lub MariaDB

Najpierw utwórz pustą bazę i konto z prawem tworzenia oraz modyfikowania tabel
w tej bazie. VerdantVice używa tabel InnoDB i `utf8mb4`.

1. Uruchom plugin raz, aby wygenerował pliki, po czym zatrzymaj serwer.
2. Ustaw `type: MYSQL` albo `type: MARIADB` w
   `plugins/VerdantVice/database.yml`.
3. W odpowiedniej sekcji wpisz `host`, `port`, `database`, `username`,
   `password` i parametry JDBC.
4. Nie publikuj tego pliku ani raportów zawierających jego zawartość.
5. Uruchom serwer i sprawdź migracje oraz `/vv admin database`.

Przykład:

```yaml
type: MARIADB
mariadb:
  host: db.internal.example
  port: 3306
  database: verdantvice
  username: verdantvice
  password: "długie-losowe-hasło"
  parameters: useUnicode=true&characterEncoding=utf8&useSSL=true
```

Zmiana typu lub danych połączenia zawsze wymaga pełnego restartu. Samo
`/vv admin reload` celowo pomija `database.yml`.

VerdantVice nie przenosi automatycznie danych pomiędzy SQLite, MySQL i
MariaDB. Zmiana typu bez migracji danych tworzy niezależny stan gry.

## 4. Opcjonalne pluginy

Wszystkie integracje są miękkie — brak pluginu nie blokuje VerdantVice.

- Vault wymaga również zgodnego providera ekonomii. Ustaw
  `economy.yml` → `provider: VAULT` albo `vault.enabled: true`.
- PlaceholderAPI udostępnia placeholdery po wykryciu pluginu.
- WorldGuard, GriefPrevention i Lands są wykrywane, jeśli odpowiadający wpis
  w `integrations.yml` ma wartość `true`.

W razie błędu adaptera ochrony decyzja staje się `UNKNOWN`. Domyślne
`region-protection-fallback: DENY_IF_UNKNOWN` bezpiecznie blokuje wtedy
budowę.

## 5. Paczka zasobów

Paczka nie jest częścią JAR-a serwerowego i nie jest wymagana. Wybierz
wariant z `resource-pack/1.21.4/` albo `resource-pack/26.2/`, spakuj jego
zawartość, umieść ZIP pod publicznym adresem HTTPS i skonfiguruj
`resourcepack.yml`.

Instrukcja wraz z obliczeniem SHA-1: [RESOURCE_PACK.md](RESOURCE_PACK.md).

## 6. Pierwsza konfiguracja

Przed udostępnieniem serwera:

1. wybierz `default-language`;
2. sprawdź limity roślin, plantacji, workerów i wyświetlaczy w `config.yml`;
3. wybierz ekonomię w `economy.yml`;
4. ustaw zasady regionów w `integrations.yml`;
5. dostosuj okres kopii SQLite;
6. skonfiguruj uprawnienia w pluginie permissions;
7. wykonaj pełny scenariusz z [TESTING.md](TESTING.md).

## 7. Aktualizacja

1. Zatrzymaj serwer — nie używaj `/reload`.
2. Skopiuj razem:
   - bazę lub jej spójny dump,
   - `data/signing.key`,
   - `data/processing-stations.yml`,
   - wszystkie pliki YAML i języki.
3. Zamień JAR na nowy produkcyjny JAR.
4. Uruchom serwer i pozwól wykonać automatyczne migracje.
5. Sprawdź log, `/vv admin database`, `/vv admin performance` i podstawowy
   cykl rozgrywki.

Nie zmieniaj ręcznie tabeli `vv_schema_history`. Pełna procedura:
[MIGRATION.md](MIGRATION.md).

## 8. Przeniesienie na inny host

Przenieś cały `plugins/VerdantVice/`, nie tylko bazę. Szczególnie ważny jest
`data/signing.key`: odpowiada za wiarygodność istniejących przedmiotów,
partii i operacji. Ustaw prawa plików tak, aby konto serwera mogło czytać i
zapisywać katalog, ale inni użytkownicy hosta nie mogli odczytać klucza ani
hasła bazy.

Po przeniesieniu bazy sieciowej zmień wyłącznie dane połączenia, a następnie
wykonaj restart. Dla SQLite sprawdź, czy ścieżka pozostaje wewnątrz
`plugins/VerdantVice/`; kod odrzuca ścieżki wychodzące poza katalog pluginu.

## 9. Usunięcie

Zatrzymaj serwer, usuń JAR, a dane zarchiwizuj. Usunięcie
`plugins/VerdantVice/` jest trwałym skasowaniem profili, plantacji, roślin,
partii, rynku, kontraktów i klucza podpisu. Przed takim krokiem zachowaj
kopię zgodną z polityką prywatności serwera.
