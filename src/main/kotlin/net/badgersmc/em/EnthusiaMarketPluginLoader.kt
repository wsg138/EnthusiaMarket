package net.badgersmc.em

import io.papermc.paper.plugin.loader.PluginClasspathBuilder
import io.papermc.paper.plugin.loader.PluginLoader
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.RemoteRepository

/**
 * Declares runtime libraries explicitly against `repo1.maven.org` instead of
 * relying on the server's configured Maven Central URL.
 *
 * Why this exists: plugin.yml's `libraries:` block resolves through whatever the
 * server admin (or fork author) set as the central repository. Leaf — and some
 * other Paper forks — hardcode `maven.aliyun.com` as central, and when that
 * mirror has an outage every plugin using runtime libraries fails to load with
 * a 502 / connection error. A `PluginLoader` runs before classloading and lets
 * us specify the repository URL ourselves, sidestepping the server config.
 *
 * Keep this list in sync with paper-plugin.yml (the file is purely informational —
 * Paper reads its libraries from this loader, not from the yml). If you add or
 * remove a runtime dep, update build.gradle.kts's shadowJar excludes too.
 */
@Suppress("UnstableApiUsage")
class EnthusiaMarketPluginLoader : PluginLoader {

    override fun classloader(builder: PluginClasspathBuilder) {
        val resolver = MavenLibraryResolver()

        resolver.addRepository(
            RemoteRepository.Builder(
                "central",
                "default",
                "https://repo1.maven.org/maven2/"
            ).build()
        )

        listOf(
            "com.zaxxer:HikariCP:5.1.0",
            "org.xerial:sqlite-jdbc:3.45.1.0",
            "org.mariadb.jdbc:mariadb-java-client:3.3.2",
            "org.slf4j:slf4j-nop:2.0.13",
            "org.jetbrains.kotlin:kotlin-stdlib:2.0.21",
            "org.jetbrains.kotlin:kotlin-reflect:2.0.21",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0",
            "com.charleskorn.kaml:kaml:0.59.0"
        ).forEach { coord ->
            resolver.addDependency(Dependency(DefaultArtifact(coord), null))
        }

        builder.addLibrary(resolver)
    }
}
