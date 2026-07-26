import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Zip

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
    filteringCharset = "UTF-8"
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.withType<ShadowJar>().configureEach {
    archiveBaseName.set("VerdantVice")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
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
    testLogging {
        events("passed", "skipped", "failed")
    }
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
