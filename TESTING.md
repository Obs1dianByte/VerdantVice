# Testowanie VerdantVice

## Testy automatyczne

Pełny test z czystego stanu:

```powershell
.\gradlew.bat clean test
```

Pełny build wydaniowy, który również uruchamia testy:

```powershell
.\gradlew.bat clean build
```

Linux/macOS:

```bash
./gradlew clean build
```

Testy modułów osobno:

```powershell
.\gradlew.bat :verdantvice-core:test
.\gradlew.bat :verdantvice-paper:test
```

Raporty HTML:

```text
verdantvice-core/build/reports/tests/test/index.html
verdantvice-paper/build/reports/tests/test/index.html
```

JUnit w Core obejmuje obecnie m.in. wzrost i pielęgnację, genetykę, zbiory,
autentyczność i cały pipeline przetwarzania, tolerancję/efekty, rynek i
limity handlu, kontrakty, profile, plantacje oraz usługi workerów. Testy
modułu Paper obejmują pomocnicze reguły rynku i wynik zatwierdzania
transakcji; test serwerowy pozostaje wymagany dla Bukkit, GUI i encji.

## Serwer deweloperski 26.2

Uruchom pod JDK 25:

```powershell
.\gradlew.bat :verdantvice-paper:runServer
```

Zadanie używa katalogu `run/` i pobiera Paper 26.2. Przeczytaj EULA
Minecraft. Jeżeli wygenerowany serwer tego wymaga, ustaw po akceptacji
`eula=true` w `run/eula.txt` i uruchom zadanie ponownie.

Katalog `run/` jest środowiskiem jednorazowym. Nie traktuj znajdującej się w
nim bazy jako kopii danych produkcyjnych.

## Macierz zgodności

Przed wydaniem wykonaj cały scenariusz co najmniej w dwóch czystych
środowiskach:

| Przypadek | Serwer | Java | Paczka |
|---|---|---:|---|
| najstarszy wspierany | Paper/Purpur 1.21.4 | 21 | `resource-pack/1.21.4` |
| najnowsza linia | Paper/Purpur 26.2 | 25 | `resource-pack/26.2` |

`runServer` pokrywa tylko drugi wiersz. Dla 1.21.4 skopiuj produkcyjny JAR do
osobnego testowego serwera. Nie współdziel świata, SQLite ani
`signing.key` między równoległymi instancjami.

## Scenariusz akceptacyjny

### 1. Start i trwałość

1. Uruchom czysty serwer.
2. Potwierdź utworzenie 17 YAML-i, sześciu języków, bazy i klucza.
3. Sprawdź w logu schemat 3, 12 odmian, 6 języków i komunikat
   `Plugin enabled successfully.`.
4. Wykonaj `/vv admin database` i `/vv admin performance`.
5. Zrestartuj serwer i sprawdź, że nie są ponownie stosowane istniejące
   migracje.
6. Dla SQLite sprawdź kopię startową po drugim uruchomieniu.

### 2. Język i GUI

1. Otwórz `/vv`, wszystkie widoki i nawigację stron.
2. Przełącz kolejno sześć języków.
3. Wyjdź, wejdź i potwierdź trwałość wyboru.
4. Sprawdź konsolę pod kątem brakujących kluczy lub błędów MiniMessage.

### 3. Plantacja

1. Wydaj `selection-wand`, zaznacz oba narożniki i utwórz plantację.
2. Sprawdź limit nazwy, maksymalny rozmiar i nakładanie regionów.
3. Zaproś członka i przetestuj role `MEMBER`, `MANAGER`, `VIEWER`.
4. Spróbuj naruszyć stół i magazyn tłokiem oraz bez uprawnień.
5. Jeśli są zainstalowane, powtórz tworzenie na terenie chronionym przez
   WorldGuard, GriefPrevention i Lands.

### 4. Roślina

1. Wydaj `planting-pot` i nasiono `berry_fog`.
2. Postaw stanowisko, posadź, zeskanuj i zastosuj każde narzędzie pielęgnacji.
3. Sprawdź minimalny odstęp, limit gracza i limit chunka.
4. Ustaw etapy administracyjnie aż do `READY`, `OVERRIPE` i `SPOILED`.
5. Zbierz w różnych oknach i sprawdź powstanie unikalnej świeżej partii.
6. Wyładuj chunk, odczekaj i sprawdź obliczenie czasu po ponownym wczytaniu.

### 5. Przetwarzanie

1. Postaw suszarnię, pojemnik dojrzewania i młynek z podpisanych przedmiotów.
2. Rozpocznij suszenie świeżej partii, zrestartuj serwer w połowie i odbierz
   wynik po upływie czasu.
3. Powtórz dla dojrzewania.
4. Zmiel partię, wymieszaj kilka zgodnych partii i rozpocznij `/vv recipes`.
5. Zamknij GUI, zgiń i wyloguj się podczas minigry; wejścia nie mogą zostać
   zgubione ani zduplikowane.
6. Spróbuj odebrać partię cudzej osoby i rozbić zajętą stację.

### 6. Bezpieczeństwo i anti-dupe

1. Skopiuj podpisany przedmiot trybem kreatywnym/anvil/craftingiem.
2. Zmodyfikuj PDC albo `CustomModelData` narzędziem testowym.
3. Spróbuj dwukrotnie sprzedać, użyć lub przetworzyć tę samą instancję.
4. Wyślij dwa równoległe kliknięcia.
5. Potwierdź, że rejestr CAS przyjmuje najwyżej jedną operację, a podrobiony
   przedmiot jest blokowany/kwarantannowany.

Testuj wyłącznie na kopii. Nie usuwaj `signing.key` na środowisku z
wartościowymi danymi.

### 7. Zielarze, rynek i kontrakty

1. Doprowadź farmera do jednorazowej ewaluacji albo użyj
   `/vv admin spawnherbalist <poziom>`.
2. Otwórz oferty, kup przedmiot i sprawdź pobranie waluty.
3. Sprzedaj poprawną partię, powtórz sprzedaż i sprawdź malejący mnożnik.
4. Wymuś/upłyń aktualizację rynku i sprawdź historię oraz wydarzenie.
5. Utwórz kontrakt, dostarcz część, zrestartuj serwer, dokończ i odbierz
   nagrodę tylko raz.
6. Sprawdź limit dzienny i limit aktywnych kontraktów.

### 8. Workerzy

1. Utwórz zielarza `VERDANT_EXPERT`, kup/zdobądź podpisany kontrakt
   zatrudnienia, trzymaj go w głównej ręce i użyj `/vv admin hireworker`,
   patrząc na zielarza na własnej plantacji.
2. Sprawdź limit workerów, brak właściwej plantacji i brak uprawnienia.
3. Obserwuj harmonogram oraz zadania: ratowanie, leczenie, podlewanie, zbiór,
   załadunek/rozładunek, sadzenie, pakowanie i czyszczenie.
4. Zablokuj ścieżkę i sprawdź retry, timeout, morale oraz opcjonalny awaryjny
   teleport.
5. Nie wypłać pensji przez kolejne dni, potem zapłać i sprawdź trwałość
   rejestru płatności.
6. Zabij workera i zrestartuj serwer przy aktywnym zadaniu; rezerwacja nie
   może zostać osierocona na stałe.

### 9. Efekty i tolerancja

1. Użyj prawidłowego końcowego produktu i potwierdź jednorazowe zużycie.
2. Powtarzaj użycie, obserwując tolerancję i blokadę silnego efektu.
3. Sprawdź zanik tolerancji po czasie rzeczywistym i restarcie.
4. Przetestuj minimalne zdrowie, maksymalny wzmacniacz i negatywne skutki.

### 10. Integracje i paczka

1. Bez zależności sprawdź ekonomię szmaragdową i materiały vanilla.
2. Z Vault sprawdź pobranie, wpłatę, brak środków i błąd providera.
3. Z PlaceholderAPI odczytaj każdy `%verdantvice_*%`.
4. Wyślij właściwy wariant paczki, odrzuć go, zaakceptuj i sprawdź wymuszanie.
5. Podaj błędny URL/SHA-1 i potwierdź kontrolowany komunikat bez wyjątku.

## Bazy danych

Test wydaniowy powinien objąć:

- czysty SQLite oraz start z istniejącym WAL;
- czystą bazę MySQL;
- czystą bazę MariaDB;
- aktualizację z każdej wspieranej poprzedniej wersji schematu;
- chwilową niedostępność bazy i retry;
- pełną kolejkę executora;
- zatrzymanie serwera przy oczekujących zapisach.

Wbudowane backupy dotyczą tylko SQLite. Dla MySQL/MariaDB przygotuj
zewnętrzny dump przed testem migracji.

## Próba obciążeniowa

Wygeneruj liczbę roślin, workerów i display entities zbliżoną do limitów
produkcji. Obserwuj:

```text
/vv admin performance
/vv admin database
```

Zwiększaj `plants-per-cycle` ostrożnie. Kryterium akceptacji to brak
długotrwałej kolejki DB, brak pełnych skanów w pojedynczym ticku i stabilny
czas ticka przy wczytywaniu chunków.

## Artefakt końcowy

Po udanych testach wykonaj:

```powershell
.\gradlew.bat clean build
```

Zainstaluj jeszcze raz dokładnie
`verdantvice-paper/build/libs/VerdantVice-1.0.0.jar` z tego builda na czystym
serwerze. Sprawdź, że `plugin.yml`, wszystkie YAML-e i języki znajdują się w
JAR-ze oraz że nie dystrybuujesz `-thin.jar`.
