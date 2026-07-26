plugins {
    base
}

group = "com.verdantvice"
version = "1.0.0"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.named("build") {
    dependsOn(":verdantvice-core:build", ":verdantvice-paper:build")
}
