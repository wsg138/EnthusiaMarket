pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "net.badgersmc.nexus.permissions") {
                useModule("com.github.BadgersMC.Nexus:nexus-permissions-gradle:${requested.version}")
            }
        }
    }
}

rootProject.name = "EnthusiaMarket"