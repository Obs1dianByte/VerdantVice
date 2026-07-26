# VerdantVice

VerdantVice to rozbudowany, fabularny symulator fikcyjnych roślin, produkcji,
plantacji i rynku dla serwerów Paper/Purpur. Rozgrywka obejmuje pielęgnację
roślin, genetykę, zbiory, suszenie i dojrzewanie partii, mieszanie, minigrę
skręcania, efekty i tolerancję, zielarzy, kontrakty, dynamiczne ceny oraz
pracowników plantacji.

Wszystkie parametry uprawy i przetwarzania są abstrakcyjnymi wartościami gry.
Projekt nie jest poradnikiem dotyczącym rzeczywistych roślin ani substancji.

## Najważniejsze funkcje

- 12 wbudowanych fikcyjnych odmian z odmiennymi cechami, rzadkością, efektami
  i cenami;
- wzrost oparty na znacznikach czasu, również po restarcie oraz — zależnie od
  konfiguracji — poza załadowanym chunkem;
- stan zdrowia, nawodnienie, odżywienie, czystość, stres, problemy i okno
  optymalnego zbioru;
- unikalne profile genetyczne i pochodzenie każdej partii;
- pełny łańcuch: świeża partia → suszenie → dojrzewanie → mielenie/mieszanie
  → minigra skręcania;
- ocena jakości, historia partii i podpis HMAC chroniący przed modyfikacją
  oraz powielaniem przedmiotów;
- zielarze, oferty, reputacja, kontrakty i rynek z historią cen oraz
  wydarzeniami;
- plantacje z regionem, rolami, magazynami, pracownikami, zadaniami, pensjami
  i morale;
- sześć języków: `pl_PL`, `en_US`, `de_DE`, `es_ES`, `cs_CZ`, `uk_UA`;
- SQLite, MySQL i MariaDB, automatyczne migracje oraz kopie SQLite;
- opcjonalne integracje z Vault, PlaceholderAPI, WorldGuard,
  GriefPrevention i Lands;
- opcjonalna paczka zasobów dla Minecraft 1.21.4 oraz 26.2;
- publiczne API v1 przez Bukkit `ServicesManager` i 12 zdarzeń Bukkit.

## Wymagania

| Serwer | Java | Artefakt |
|---|---:|---|
| Paper/Purpur 1.21.4 | 21 lub nowsza zgodna z serwerem | `VerdantVice-1.0.0.jar` |
| Paper/Purpur 26.2 | 25 | ten sam `VerdantVice-1.0.0.jar` |

Plugin jest kompilowany jako bytecode Java 21 przeciwko API Paper 1.21.4 i
korzysta ze wspólnego, publicznego API Bukkit/Paper. Nie należy instalować
pliku z końcówką `-thin.jar`.

## Instalacja

1. Zatrzymaj serwer.
2. Skopiuj `VerdantVice-1.0.0.jar` do katalogu `plugins/`.
3. Uruchom serwer. VerdantVice utworzy `plugins/VerdantVice/`, konfigurację,
   języki, bazę SQLite i klucz podpisu.
4. Zaczekaj na komunikat `Plugin enabled successfully.` w logu VerdantVice.
5. Wejdź na serwer i użyj `/vv guide` albo `/vv`.

Szczegółowa procedura, konfiguracja MySQL/MariaDB i aktualizacja:
[INSTALLATION.md](INSTALLATION.md).

## Szybki start administratora

Poniższy scenariusz korzysta z domyślnej ekonomii opartej na szmaragdach:

1. `/vv admin give <gracz> selection-wand`
2. Zaznacz lewym i prawym kliknięciem dwa narożniki regionu.
3. `/vv farm create Moja Plantacja`
4. `/vv admin give <gracz> planting-pot`
5. `/vv admin seed <gracz> berry_fog`
6. Postaw donicę i kliknij ją nasionem.
7. Do testów użyj `/vv admin inspect` oraz `/vv admin setstage READY`,
   patrząc na roślinę.
8. Po zbiorze ustaw stacje poleceniem `admin give` i przejdź przez suszenie,
   dojrzewanie oraz mielenie.

Jeśli region nie został zaznaczony, `/vv farm create` tworzy domyślny obszar
17×33×17 wokół gracza. Region użytkownika może mieć najwyżej 128 bloków na
każdej osi i 2 000 000 bloków objętości.

## Najczęstsze komendy

- `/vv` — główne GUI;
- `/vv guide` — lokalizowana książka wprowadzająca;
- `/vv farm create <nazwa>` — tworzy plantację;
- `/vv market` — rynek;
- `/vv contracts` — kontrakty;
- `/vv workers` — pracownicy;
- `/vv language <kod>` — zapisuje język gracza;
- `/vv admin reload` — bezpiecznie przeładowuje konfigurację;
- `/vv admin database` — stan bazy i kolejki;
- `/vv admin debug` — zanonimizowany raport diagnostyczny.

Aliasem `/verdantvice` jest `/vv`. Pełna, zgodna z implementacją lista:
[COMMANDS.md](COMMANDS.md). Uprawnienia: [PERMISSIONS.md](PERMISSIONS.md).

## Zależności i integracje

VerdantVice działa samodzielnie. Domyślna ekonomia pobiera i wypłaca fizyczne
szmaragdy.

| Plugin | Zastosowanie | Wymagany |
|---|---|---|
| Vault + provider ekonomii | saldo zamiast fizycznych szmaragdów | nie |
| PlaceholderAPI | placeholdery `%verdantvice_*%` | nie |
| WorldGuard | kontrola prawa budowy | nie |
| GriefPrevention | kontrola prawa budowy | nie |
| Lands | kontrola zaufania do terenu | nie |

`plugin.yml` deklaruje również miękkie zależności ItemsAdder, Oraxen i
LuckPerms. W tej wersji nie mają osobnego adaptera wykonawczego; wbudowane
modele używają `CustomModelData`, a uprawnienia są standardowymi
uprawnieniami Bukkit.

## Dane i baza

Domyślnie dane znajdują się w
`plugins/VerdantVice/data/verdantvice.db`. SQLite działa w trybie WAL, ma
włączone klucze obce i jest obsługiwany przez pojedynczą kolejkę operacji.
MySQL i MariaDB używają puli HikariCP. Schemat jest migrowany automatycznie
przed przejściem pluginu do stanu gotowości.

Kopie SQLite trafiają do `plugins/VerdantVice/backups/`. Własne kopie MySQL
lub MariaDB wykonuje administrator narzędziami serwera bazy.

Plik `plugins/VerdantVice/data/signing.key` jest tak samo ważny jak baza.
Jego utrata unieważnia istniejące przedmioty VerdantVice. Zawsze kopiuj go
razem z bazą oraz `data/processing-stations.yml`.

## Znane ograniczenia

- Paczka zasobów jest opcjonalna i musi być hostowana przez administratora
  pod adresem HTTPS; bez niej używane są materiały vanilla.
- Zadanie deweloperskie `runServer` jest skonfigurowane wyłącznie dla Paper
  26.2. Test 1.21.4 wymaga osobnego serwera.
- Serwis publicznego API jest rejestrowany dopiero po asynchronicznym
  uruchomieniu bazy. Plugin zależny powinien pobierać usługę leniwie i
  obsłużyć chwilowy brak usługi podczas startu.
- Flagi `modules.*`, `security.require-signed-items` oraz wybrane ustawienia
  integracji ItemsAdder/Oraxen/LuckPerms są obecnie ładowane jako
  konfiguracja, ale nie wyłączają ani nie zastępują odpowiadających im
  systemów wykonawczych.
- Węzły uprawnień zadeklarowane w `plugin.yml` nie zawsze są osobnymi
  kontrolami dla każdej akcji; dokładny stan opisuje
  [PERMISSIONS.md](PERMISSIONS.md).
- Projekt jest przeznaczony dla Paper/Purpur. Nie deklaruje obsługi Folia,
  Spigot ani CraftBukkit.

## Zgłaszanie błędów

Przed zgłoszeniem:

1. wykonaj `/vv admin debug`;
2. zapisz wersję Paper/Purpur, Minecrafta, Javy i VerdantVice;
3. dołącz dokładne kroki odtworzenia oraz istotny fragment logu;
4. podaj używany typ bazy i listę opcjonalnych integracji;
5. nie publikuj `database.yml`, pliku `signing.key`, pełnego zrzutu bazy ani
   danych graczy.

Raport diagnostyczny trafia do
`plugins/VerdantVice/logs/diagnostic-<czas>.yml` i celowo nie zawiera hasła
bazy ani klucza podpisu. Przekaż raport i opis opiekunowi projektu w używanym
przez zespół systemie zgłoszeń.

## Dokumentacja

- [Budowanie](BUILDING.md)
- [Instalacja](INSTALLATION.md)
- [Konfiguracja](CONFIGURATION.md)
- [Komendy](COMMANDS.md)
- [Uprawnienia](PERMISSIONS.md)
- [Publiczne API](API.md)
- [Rozwój projektu](DEVELOPMENT.md)
- [Testowanie](TESTING.md)
- [Paczka zasobów](RESOURCE_PACK.md)
- [Migracje i aktualizacje](MIGRATION.md)
- [Rozwiązywanie problemów](TROUBLESHOOTING.md)
