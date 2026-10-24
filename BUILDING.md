# Building VerdantVice

## Development requirements

- JDK 21 or newer for compilation;
- JDK 25 to run the `runServer` task, which uses Paper 26.2;
- Internet access when first running the wrapper to download the Gradle
  distribution, dependencies and test Paper server;
- Git is not required by the build process itself.

The repository includes the Gradle 9.6.1 wrapper. Do not install a global
Gradle distribution or replace the wrapper with an arbitrary version.

Check the active Java installation:

```powershell
java -version
.\gradlew.bat --version
```

On Linux/macOS:

```bash
java -version
./gradlew --version
```

If the host has several JDKs, set `JAVA_HOME` to the appropriate installation
before invoking the wrapper.

## Clean build

Windows:

```powershell
.\gradlew.bat clean build
```

Linux/macOS:

```bash
./gradlew clean build
```

The main task builds both modules, runs JUnit 5 tests, generates ordinary
module JARs and assembles the production shaded Paper JAR.

Main outputs:

```text
verdantvice-paper/build/libs/VerdantVice-1.0.0.jar
verdantvice-paper/build/libs/VerdantVice-1.0.0-thin.jar
verdantvice-core/build/libs/verdantvice-core-1.0.0.jar
```

Install only `VerdantVice-1.0.0.jar` on the server. It includes VerdantVice
Core, relocated HikariCP and Gson, and the SQLite, MySQL and MariaDB drivers.
The `-thin.jar` file does not contain the complete runtime.

Compilation uses `--release 21`, UTF-8 encoding, `-parameters` and
`-Xlint:all` warnings. The Paper API and PlaceholderAPI are `compileOnly`
dependencies; they are not packaged in the JAR.

## Useful tasks

```powershell
# All tests
.\gradlew.bat test

# Domain module only, without Bukkit
.\gradlew.bat :verdantvice-core:test

# Paper adapter only
.\gradlew.bat :verdantvice-paper:test

# Production JAR only
.\gradlew.bat :verdantvice-paper:shadowJar

# Task list
.\gradlew.bat tasks
```

## Paper 26.2 test server

The `runServer` task from `xyz.jpenilla.run-paper` uses the `run/` directory
and Minecraft/Paper version `26.2`:

```powershell
.\gradlew.bat :verdantvice-paper:runServer
```

Run it with JDK 25. The first execution downloads the server and may take
longer. The `run/` directory is ignored by the repository.

Read the Minecraft EULA before starting the server. If the server stops to
request acceptance, open the generated `run/eula.txt` and, only after
consciously accepting the terms, set:

```text
eula=true
```

Then run the task again. Do not include the EULA file or test world in the
plugin artifact.

After startup, check the console for:

- successful database migrations;
- the VerdantVice message `Plugin enabled successfully.`;
- no exceptions while registering listeners and the API;
- the output of `/vv admin database` and `/vv admin performance`.

For the full gameplay test scenario and the 1.21.4/26.2 matrix, see
[TESTING.md](TESTING.md).

## Paper 1.21.4 test

The Gradle configuration does not provide a second server task. Build the
JAR and place it manually in a clean Paper/Purpur 1.21.4 server running
Java 21:

```powershell
.\gradlew.bat clean build
Copy-Item .\verdantvice-paper\build\libs\VerdantVice-1.0.0.jar `
  C:\servers\paper-1.21.4\plugins\
```

Use a separate database and world from the 26.2 test.

## Reproducibility and caches

Parallel tasks, the build cache and the configuration cache are enabled.
Always run `clean build` before a release, rather than only `shadowJar`.
Do not include `.gradle/`, `build/`, `run/`, test databases or logs in the
distribution.
