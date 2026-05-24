plugins {
    kotlin("jvm") version "2.0.0"
    id("com.gradleup.shadow") version "8.3.6"
    idea
}

group = "net.badgersmc.em"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://jitpack.io")
    maven("https://maven.enginehub.org/repo/") // WorldGuard
    maven("https://repo.opencollab.dev/main/")  // Floodgate / Cumulus
    mavenLocal() // Nexus local build
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.9")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("org.geysermc.cumulus:cumulus:2.0.0-SNAPSHOT")
    testImplementation("org.geysermc.cumulus:cumulus:2.0.0-SNAPSHOT")
    testImplementation("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")

    // IFramework for Java GUIs
    compileOnly("com.github.stefvanschie.inventoryframework:IF:0.11.6")
    testImplementation("com.github.stefvanschie.inventoryframework:IF:0.11.6")
    testImplementation("commons-lang:commons-lang:2.6")

    // Nexus DI + config + coroutines (shaded)
    implementation("net.badgersmc:nexus-core:1.6.0")
    // Nexus Paper commands + BukkitDispatcher (shaded)
    implementation("net.badgersmc:nexus-paper:1.6.0")

    // Database
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.3.2")

    // Logging (Nexus uses SLF4J)
    implementation("org.slf4j:slf4j-nop:2.0.13")

    // Kotlin
    shadow("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.107.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testImplementation("com.lemonappdev:konsist:0.17.3")

    // LumaGuilds API for real GuildProvider implementation
    compileOnly(files("/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar"))
    testImplementation(files("/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    test { useJUnitPlatform() }
    shadowJar {
        archiveClassifier.set("")
        relocate("net.badgersmc.nexus", "net.badgersmc.em.libs.nexus")
        relocate("io.github.classgraph", "net.badgersmc.em.libs.classgraph")
        relocate("nonapi.io.github.classgraph", "net.badgersmc.em.libs.nonapi.classgraph")
    }
    build { dependsOn(shadowJar) }
}