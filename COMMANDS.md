# Komendy VerdantVice

Główna komenda to `/verdantvice`, alias `/vv`. Bukkit wymaga do jej użycia
`verdantvice.use`. Bez argumentów otwiera główne GUI graczowi.

Poniżej opisano zachowanie faktycznego executora. Część tekstów `usage` w
plikach językowych zawiera przyszłe, szersze warianty argumentów; nie są one
automatycznie obsługiwane przez bieżący parser.

## Komendy gracza

| Komenda | Działanie | Konsola |
|---|---|---|
| `/vv` | otwiera główne GUI | nie |
| `/vv help` | pokazuje dostępne komendy; argument strony nie jest używany | tak |
| `/vv guide` | wydaje, rejestruje i otwiera podstawową książkę | nie |
| `/vv profile` | otwiera widok umiejętności bieżącego gracza | nie |
| `/vv skills` | otwiera widok umiejętności | nie |
| `/vv strains` | otwiera katalog odmian | nie |
| `/vv recipes` | próbuje rozpocząć minigrę skręcania z wymaganych przedmiotów | nie |
| `/vv market` | otwiera widok rynku | nie |
| `/vv contracts` | otwiera widok kontraktów | nie |
| `/vv farm` | otwiera zarządzanie plantacją | nie |
| `/vv workers` | otwiera zarządzanie pracownikami | nie |
| `/vv language <kod>` | zapisuje język gracza | nie |
| `/vv notifications` | otwiera ustawienia powiadomień | nie |
| `/vv notifications <typ> <on\|off>` | przełącza typ powiadomienia | nie |
| `/vv resourcepack` | ponownie wysyła skonfigurowaną paczkę | nie |

Obsługiwane kody języka:

```text
pl_PL, en_US, de_DE, es_ES, cs_CZ, uk_UA
```

Typ powiadomienia można podać małymi literami i z łącznikiem, np.
`plant-ready`, `drying-complete`, `market-change`.

## Genetyka

`/vv genetics` otwiera laboratorium i listę własnych linii. Aby utworzyć nową
linię, trzymaj dwa różne, podpisane nasiona w głównej i pomocniczej ręce, a
następnie użyj `/vv genetics <nazwa>`. Oba nasiona są oznaczane jako zużyte,
a po udanym skrzyżowaniu gracz otrzymuje jedno podpisane nasiono nowej linii.
Nazwa ma 3–24 dozwolone znaki i nie może zawierać słów z `genetics.yml`.

## Plantacje

| Komenda | Działanie |
|---|---|
| `/vv farm create <nazwa>` | tworzy plantację z bieżącego zaznaczenia albo domyślnego regionu |
| `/vv farm delete` | natychmiast usuwa plantację należącą do wykonującego |
| `/vv farm invite <gracz-online>` | dodaje gracza online z rolą `MEMBER` |
| `/vv farm remove <gracz>` | usuwa członka; działa również dla gracza offline |

Nazwa przy tworzeniu ma 3–48 znaków. `delete` nie ma w executorze dodatkowego
argumentu ani interaktywnego potwierdzenia, mimo szerszego tekstu w pliku
językowym. Przed użyciem sprawdź właściwą plantację.

Zaznaczenie regionu wykonuje się podpisaną `selection-wand`: lewy przycisk
ustawia pierwszy narożnik, prawy drugi. Brak zaznaczenia tworzy region wokół
gracza.

## Komendy administracyjne

Każda poniższa komenda wymaga najpierw `verdantvice.admin`.

| Komenda | Działanie | Dodatkowe wymaganie |
|---|---|---|
| `/vv admin reload` | bezpiecznie przeładowuje konfigurację i języki poza DB | `verdantvice.admin.reload` |
| `/vv admin save` | zapisuje wszystkie załadowane profile | — |
| `/vv admin give <gracz> <typ> [odmiana\|ilość] [ilość]` | wydaje i rejestruje podpisane przedmioty; ilość 1–64 | `verdantvice.admin.give` |
| `/vv admin seed <gracz> <odmiana>` | skrót wydający jedno podpisane nasiono | `verdantvice.admin.give` |
| `/vv admin plant <odmiana>` | sadzi na wskazanym z odległości 6 bloków stanowisku | gracz |
| `/vv admin setstage <etap>` | zmienia etap wskazanej rośliny | gracz |
| `/vv admin setquality <klasa>` | zastępuje trzymaną partię wersją o wskazanej jakości | gracz |
| `/vv admin spawnherbalist [poziom]` | konwertuje villagera wskazanego do 8 bloków lub tworzy farmera nad wskazanym blokiem; domyślnie `NOVICE` | gracz |
| `/vv admin hireworker` | uruchamia normalne zatrudnienie villagera wskazanego do 8 bloków | gracz |
| `/vv admin removeplant` | bezpiecznie usuwa wskazaną roślinę | gracz |
| `/vv admin inspect` | pokazuje dokładny stan wskazanej rośliny | gracz |
| `/vv admin market` | otwiera GUI rynku | gracz |
| `/vv admin contract` | otwiera GUI kontraktów | gracz |
| `/vv admin resetplayer <gracz> confirm` | resetuje profil gracza | — |
| `/vv admin migrate` | zwraca wynik inicjalizacji/migracji bieżącego managera DB | — |
| `/vv admin database` | pokazuje typ, stan, aktywne operacje i kolejkę DB | — |
| `/vv admin debug` | zapisuje zanonimizowany raport w `logs/` | — |
| `/vv admin performance` | pokazuje liczbę roślin, plantacji i kolejkę DB | — |

`/vv admin migrate` nie zastępuje restartu po instalacji nowszego JAR-a.
Manager bazy przechowuje wynik startu, a właściwe oczekujące migracje są
wykonywane przed gotowością pluginu.

`spawnherbalist` przyjmuje:

```text
NOVICE, APPRENTICE, JOURNEYMAN, EXPERT, MASTER, VERDANT_EXPERT
```

Jeżeli cel jest villagerem, komenda ustawia mu profesję `FARMER` i wymusza
rejestrację. Bez celu tworzy nowego farmera nad blokiem wskazanym do 8
bloków, a gdy bloku nie ma — przy graczu. Nadal obowiązuje limit zielarzy w
promieniu wioski; nieudane tworzenie usuwa nowo przywołaną encję.

`hireworker` nie omija zasad zatrudnienia tylko dlatego, że wywołano je jako
komendę admina. Cel musi być zarejestrowanym zielarzem poziomu
`VERDANT_EXPERT`, gracz musi trzymać w głównej ręce poprawnie podpisany
`PLANTATION_CONTRACT`, a manager musi znaleźć jego plantację z wolnym
miejscem. Kontrakt jest atomowo zużywany dopiero w udanej operacji.

## Identyfikatory dla `admin give`

Parser ignoruje wielkość liter i zamienia łącznik na podkreślenie. Dostępne
typy:

```text
seed
planting-pot
watering-can
plant-scanner
cleaning-kit
treatment-spray
nutrient-pack
repair-kit
drying-rack
curing-container
grinder
farm-manager-table
selection-wand
rolling-paper
filter
fresh-batch
dry-batch
cured-batch
ground-product
mixture
package
joint
plantation-contract
guide-book
advanced-book
```

Dla `seed` wymagany jest poprawny identyfikator odmiany:

```text
/vv admin give Ala seed berry_fog 8
/vv admin seed Ala berry_fog
```

Wydawanie stanowych produktów (`fresh-batch`, `joint` itd.) samą komendą nie
tworzy odpowiadającej im pełnej historii produkcji. Do testów integralności
preferuj przedmioty powstałe przez normalny proces.

## Etapy i jakości

Etapy rośliny:

```text
SEED, SPROUT, SEEDLING, VEGETATIVE, MATURING, READY, OVERRIPE, SPOILED
```

Klasy jakości:

```text
SPOILED, VERY_POOR, STREET, COMMON, GOOD, PREMIUM,
TOP_SHELF, PERFECT_BATCH, VERDANT_GRADE
```

## Uzupełnianie i błędy

Tab-completion podpowiada główne komendy, operacje plantacji, języki, typy
powiadomień, komendy admina, graczy online, etapy, jakości, typy przedmiotów
i odmiany.

Podczas asynchronicznego startu komendy odpowiadają komunikatem
`not ready`. Błąd bazy lub operacji ma bezpieczny, lokalizowany komunikat, a
szczegóły trafiają do logu serwera. Akcje administracyjne są logowane z nazwą
wykonującego.
