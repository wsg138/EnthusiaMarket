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
    maven("https://repo.aikar.co/content/groups/aikar/")
    maven("https://jitpack.io")
    maven("https://maven.enginehub.org/repo/") // WorldGuard
    maven("https://repo.opencollab.dev/main/")  // Floodgate / Cumulus
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.9")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("org.geysermc.cumulus:cumulus:2.0.0-SNAPSHOT")

    implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")
    implementation("co.aikar:idb-core:1.0.0-SNAPSHOT")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.3.2")
    implementation("io.insert-koin:koin-core:4.0.2")
    implementation("org.slf4j:slf4j-nop:2.0.13")

    shadow("org.jetbrains.kotlin:kotlin-stdlib")

    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.107.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    test { useJUnitPlatform() }
    shadowJar {
        archiveClassifier.set("")
        relocate("co.aikar.commands", "net.badgersmc.em.libs.acf")
        relocate("co.aikar.idb", "net.badgersmc.em.libs.idb")
        relocate("org.koin", "net.badgersmc.em.libs.koin")
    }
    build { dependsOn(shadowJar) }
}
