# Budowanie VerdantVice

## Wymagania deweloperskie

- JDK 21 lub nowsze do kompilacji;
- JDK 25 do uruchomienia zadania `runServer`, ponieważ używa Paper 26.2;
- dostęp do Internetu przy pierwszym uruchomieniu wrappera, aby pobrać
  dystrybucję Gradle, zależności i testowy serwer Paper;
- Git nie jest wymagany przez sam proces budowania.

Repozytorium zawiera wrapper Gradle 9.6.1. Nie instaluj globalnego Gradle i
nie zastępuj wrappera przypadkową wersją.

Sprawdzenie aktywnej Javy:

```powershell
java -version
.\gradlew.bat --version
```

Na Linux/macOS:

```bash
java -version
./gradlew --version
```

Jeśli na hoście jest kilka JDK, ustaw `JAVA_HOME` na właściwą instalację
przed wywołaniem wrappera.

## Czysty build

Windows:

```powershell
.\gradlew.bat clean build
```

Linux/macOS:

```bash
./gradlew clean build
```

Zadanie główne buduje oba moduły, uruchamia testy JUnit 5, generuje zwykłe
JAR-y modułów i składa produkcyjny, zacieniony JAR Paper.

Najważniejsze wyniki:

```text
verdantvice-paper/build/libs/VerdantVice-1.0.0.jar
verdantvice-paper/build/libs/VerdantVice-1.0.0-thin.jar
verdantvice-core/build/libs/verdantvice-core-1.0.0.jar
```

Do serwera instaluj wyłącznie `VerdantVice-1.0.0.jar`. Zawiera on
VerdantVice Core oraz relokowane HikariCP i Gson, a także sterowniki SQLite,
MySQL i MariaDB. Plik `-thin.jar` nie zawiera kompletnego środowiska
wykonawczego.

Kompilacja ustawia `--release 21`, kodowanie UTF-8, `-parameters` oraz
ostrzeżenia `-Xlint:all`. API Paper i PlaceholderAPI są zależnościami
`compileOnly`; nie są pakowane do JAR-a.

## Przydatne zadania

```powershell
# wszystkie testy
.\gradlew.bat test

# tylko domena bez Bukkit
.\gradlew.bat :verdantvice-core:test

# tylko adapter Paper
.\gradlew.bat :verdantvice-paper:test

# sam produkcyjny JAR
.\gradlew.bat :verdantvice-paper:shadowJar

# lista zadań
.\gradlew.bat tasks
```

## Testowy serwer Paper 26.2

Zadanie `runServer` z pluginu `xyz.jpenilla.run-paper` używa katalogu `run/`
i wersji Minecraft/Paper `26.2`:

```powershell
.\gradlew.bat :verdantvice-paper:runServer
```

Uruchamiaj je pod JDK 25. Pierwsze wykonanie pobiera serwer i może potrwać
dłużej. Katalog `run/` jest ignorowany przez repozytorium.

Przed uruchomieniem serwera zapoznaj się z EULA Minecraft. Jeśli serwer
zatrzyma się z prośbą o akceptację, otwórz wygenerowany `run/eula.txt`, ustaw
po świadomej akceptacji:

```text
eula=true
```

i uruchom zadanie ponownie. Nie umieszczaj pliku EULA ani świata testowego w
artefakcie pluginu.

Po starcie sprawdź w konsoli:

- zastosowanie migracji bazy bez błędu;
- komunikat VerdantVice `Plugin enabled successfully.`;
- brak wyjątków podczas rejestracji listenerów i API;
- `/vv admin database` oraz `/vv admin performance`.

Pełny scenariusz testu gry i macierz 1.21.4/26.2:
[TESTING.md](TESTING.md).

## Test Paper 1.21.4

Konfiguracja Gradle nie ma drugiego zadania serwerowego. Zbuduj JAR i umieść
go ręcznie w czystym serwerze Paper/Purpur 1.21.4 działającym pod Javą 21:

```powershell
.\gradlew.bat clean build
Copy-Item .\verdantvice-paper\build\libs\VerdantVice-1.0.0.jar `
  C:\serwery\paper-1.21.4\plugins\
```

Użyj osobnej bazy i osobnego świata niż dla testu 26.2.

## Powtarzalność i cache

Projekt ma włączone równoległe zadania, build cache i configuration cache.
Przed wydaniem zawsze wykonaj `clean build`, a nie tylko `shadowJar`.
Nie kopiuj do dystrybucji katalogów `.gradle/`, `build/`, `run/`,
testowych baz ani logów.
