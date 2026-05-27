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

    // Nexus releases — GitHub Packages. Needs gpr.user + gpr.token in
    // ~/.gradle/gradle.properties (or GITHUB_ACTOR + GITHUB_TOKEN env on CI).
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/BadgersMC/nexus")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.token").orNull
                ?: System.getenv("GITHUB_TOKEN")
        }
    }

    // Opt-in to a locally-published Nexus snapshot: ./gradlew -PuseMavenLocal=true …
    if (providers.gradleProperty("useMavenLocal").orNull == "true") {
        mavenLocal()
    }
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

    // Nexus DI + config + coroutines — SHADED (not on Maven Central, so can't use
    // Paper's runtime library loader). Transitive kotlin-reflect, coroutines, and
    // kaml come along on compile/test classpath but are excluded from the shadowJar
    // below (Paper downloads them at runtime via the libraries: block in plugin.yml).
    implementation("net.badgersmc:nexus-core:1.11.0")
    implementation("net.badgersmc:nexus-paper:1.11.0")
    implementation("net.badgersmc:nexus-resources:1.11.0")
    implementation("net.badgersmc:nexus-i18n:1.11.0")
    implementation("net.badgersmc:nexus-persistence:1.11.0")
    implementation("net.badgersmc:nexus-scheduler:1.11.0")
    implementation("net.badgersmc:nexus-paper-gui:1.11.0")
    implementation("net.badgersmc:nexus-paper-bedrock:1.11.0")
    implementation("net.badgersmc:nexus-paper-listeners:1.11.0")
    implementation("net.badgersmc:nexus-vault:1.11.0")
    implementation("net.badgersmc:nexus-paper-loader:1.11.0")

    // Runtime-downloaded by Paper via plugin.yml `libraries:` — kept on compile +
    // test classpath but excluded from the shaded jar to shrink it from ~27 MB
    // to ~3-4 MB. If you change a version here, update plugin.yml to match.
    compileOnly("com.zaxxer:HikariCP:5.1.0")
    testImplementation("com.zaxxer:HikariCP:5.1.0")
    compileOnly("org.xerial:sqlite-jdbc:3.45.1.0")
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:3.3.2")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.3.2")
    compileOnly("org.slf4j:slf4j-nop:2.0.13")
    testImplementation("org.slf4j:slf4j-nop:2.0.13")

    // Kotlin stdlib — runtime-downloaded by Paper (see plugin.yml libraries:).
    // Auto-add disabled via kotlin.stdlib.default.dependency=false in gradle.properties.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")

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
    // Path can be overridden via -Plumaguilds.jar=... or LUMAGUILDS_JAR env var
    val lumaguildsJar = System.getenv("LUMAGUILDS_JAR") ?: project.findProperty("lumaguilds.jar")?.toString()
        ?: "/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar"
    compileOnly(files(lumaguildsJar))
    testImplementation(files(lumaguildsJar))
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
        // Drop Nexus's transitive heavyweights from the shaded jar — Paper downloads
        // them at runtime via plugin.yml `libraries:`. Keep versions in sync there.
        dependencies {
            exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib:.*"))
            exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-common:.*"))
            exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-jdk7:.*"))
            exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-jdk8:.*"))
            exclude(dependency("org.jetbrains.kotlin:kotlin-reflect:.*"))
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:.*"))
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:.*"))
            exclude(dependency("com.charleskorn.kaml:kaml:.*"))
            exclude(dependency("com.charleskorn.kaml:kaml-jvm:.*"))
            exclude(dependency("it.krzeminski:snakeyaml-engine-kmp:.*"))
            exclude(dependency("it.krzeminski:snakeyaml-engine-kmp-jvm:.*"))
            exclude(dependency("com.squareup.okio:okio:.*"))
            exclude(dependency("com.squareup.okio:okio-jvm:.*"))
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-core:.*"))
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:.*"))
        }
    }
    build { dependsOn(shadowJar) }
}