import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

dependencies {
    api(project(":verdantvice-core"))

    // Compile against the oldest supported Paper API. The resulting Java 21
    // bytecode and public Bukkit/Paper API calls also run on Paper 26.2 / Java 25.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.12.3")

    implementation("com.zaxxer:HikariCP:6.3.3")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.mysql:mysql-connector-j:9.4.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val pluginVersion = version.toString()

tasks.processResources {
    val expandedVersion = pluginVersion
    filteringCharset = "UTF-8"
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to expandedVersion)
    }
}

tasks.withType<ShadowJar>().configureEach {
    archiveBaseName.set("VerdantVice")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    relocate("com.zaxxer.hikari", "com.verdantvice.lib.hikari")
    relocate("com.google.gson", "com.verdantvice.lib.gson")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.jar {
    archiveClassifier.set("thin")
}

tasks.assemble {
    dependsOn(tasks.shadowJar, "resourcePacks")
}

tasks.test {
    useJUnitPlatform()
    inputs.dir(rootProject.layout.projectDirectory.dir("resource-pack"))
    inputs.files(rootProject.fileTree(rootProject.projectDir) {
        include("*.md", "*.txt", "*.gradle.kts", "*.properties", "*.bat", "gradlew", ".gitignore")
        include(".github/**", "gradle/**", "scripts/**", "resource-pack/**")
        include("verdantvice-core/src/**", "verdantvice-paper/src/**")
        include("verdantvice-core/build.gradle.kts", "verdantvice-paper/build.gradle.kts")
        exclude("**/__pycache__/**")
    }).withPropertyName("repositoryLanguagePolicySources")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Kept outside the normal build: a developer or CI explicitly opts into Docker.
val networkTest = sourceSets.create("networkTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[networkTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[networkTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(networkTest.implementationConfigurationName, "org.testcontainers:testcontainers-mysql:2.0.5")
    add(networkTest.implementationConfigurationName, "org.testcontainers:testcontainers-mariadb:2.0.5")
}
tasks.register<Test>("networkDatabaseTest") {
    group = "verification"
    description = "Tests real MySQL and MariaDB in disposable Docker containers (optional)."
    testClassesDirs = networkTest.output.classesDirs
    classpath = networkTest.runtimeClasspath
    useJUnitPlatform()
    systemProperty("verdantvice.network.requireDocker", providers.environmentVariable("CI").orElse("false").get())
    testLogging { events("passed", "skipped", "failed") }
}

tasks.runServer {
    minecraftVersion("26.2")
    runDirectory.set(rootProject.layout.projectDirectory.dir("run"))
    dependsOn(tasks.shadowJar)
}

val resourcePackSource = rootProject.layout.projectDirectory.dir("resource-pack")

val resourcePack1214 = tasks.register<Zip>("resourcePack1214") {
    group = "distribution"
    description = "Builds the optional resource pack for Minecraft 1.21.4."
    from(resourcePackSource.dir("1.21.4"))
    archiveFileName.set("VerdantVice-resourcepack-1.21.4.zip")
    destinationDirectory.set(layout.buildDirectory.dir("resource-packs"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

val resourcePack262 = tasks.register<Zip>("resourcePack262") {
    group = "distribution"
    description = "Builds the optional resource pack for Minecraft 26.2."
    from(resourcePackSource.dir("26.2"))
    archiveFileName.set("VerdantVice-resourcepack-26.2.zip")
    destinationDirectory.set(layout.buildDirectory.dir("resource-packs"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

tasks.register("resourcePacks") {
    group = "distribution"
    description = "Builds both supported optional resource-pack archives."
    dependsOn(resourcePack1214, resourcePack262)
}

val verifyDistribution = tasks.register("verifyDistribution") {
    val distributionVersion = pluginVersion
    group = "verification"
    description = "Checks the actual distributable JAR and both resource-pack archives."
    dependsOn(tasks.shadowJar, resourcePack1214, resourcePack262)
    val pluginJar = tasks.shadowJar.flatMap { it.archiveFile }
    val bundledResources = fileTree("src/main/resources").files.map {
        it.relativeTo(file("src/main/resources")).invariantSeparatorsPath
    }
    val packs = listOf(
        resourcePackSource.dir("1.21.4").asFile to resourcePack1214.flatMap { it.archiveFile },
        resourcePackSource.dir("26.2").asFile to resourcePack262.flatMap { it.archiveFile }
    )
    val packContents = packs.map { (source, archive) ->
        archive to fileTree(source).files.map {
            it.relativeTo(source).invariantSeparatorsPath to it
        }
    }
    inputs.file(pluginJar)
    packs.forEach { inputs.file(it.second) }
    inputs.dir(resourcePackSource)
    doLast {
        ZipFile(pluginJar.get().asFile).use { jar ->
            val required = listOf(
                "plugin.yml", "com/verdantvice/VerdantVicePlugin.class",
                "com/verdantvice/genetics/GeneticsService.class",
                "com/verdantvice/lib/hikari/HikariDataSource.class",
                "com/verdantvice/lib/gson/Gson.class", "org/sqlite/JDBC.class",
                "com/mysql/cj/jdbc/Driver.class", "org/mariadb/jdbc/Driver.class",
                "META-INF/services/java.sql.Driver"
            ) + bundledResources
            required.forEach { path ->
                check(jar.getEntry(path) != null) { "Distribution is missing $path" }
            }
            val descriptor = jar.getInputStream(jar.getEntry("plugin.yml"))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(descriptor.lineSequence().any {
                it.trim() == "version: '$distributionVersion'" || it.trim() == "version: $distributionVersion"
            }) {
                "Plugin descriptor version was not expanded"
            }
            val drivers = jar.getInputStream(jar.getEntry("META-INF/services/java.sql.Driver"))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            listOf("org.sqlite.JDBC", "com.mysql.cj.jdbc.Driver", "org.mariadb.jdbc.Driver")
                .forEach { check(drivers.contains(it)) { "Missing JDBC service provider: $it" } }
            check(jar.entries().asSequence().any {
                it.name.startsWith("org/sqlite/native/") && it.name.endsWith("sqlitejdbc.dll")
            }) { "SQLite Windows native library is missing" }
            check(jar.entries().asSequence().none {
                it.name.startsWith("org/bukkit/") || it.name.startsWith("io/papermc/paper/")
            }) { "Distribution must not bundle the server API" }
        }
        packContents.forEach { (archive, originals) ->
            ZipFile(archive.get().asFile).use { zip ->
                listOf("pack.mcmeta", "pack.png").forEach {
                    check(zip.getEntry(it) != null) { "Resource pack is missing $it" }
                }
                originals.forEach { (path, original) ->
                    val entry = checkNotNull(zip.getEntry(path)) { "Resource pack is missing $path" }
                    check(zip.getInputStream(entry).use { it.readBytes() }
                        .contentEquals(original.readBytes())) { "Resource pack changed $path" }
                }
            }
        }
        logger.lifecycle("Verified production JAR, JDBC drivers and both complete resource packs.")
    }
}

tasks.check {
    dependsOn(verifyDistribution)
}
