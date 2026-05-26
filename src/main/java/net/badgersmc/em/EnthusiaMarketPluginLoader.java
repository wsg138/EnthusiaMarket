package net.badgersmc.em;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Declares runtime libraries against repo1.maven.org directly so we don't
 * depend on whatever Maven Central mirror the server happens to be configured
 * with (Leaf forks hardcode maven.aliyun.com, which has had outages).
 *
 * IMPORTANT: this class must be Java, not Kotlin. PluginLoader.classloader()
 * runs before any runtime libraries are resolved — it's the thing that
 * schedules them — so the loader's bytecode cannot reference kotlin-stdlib
 * classes (Intrinsics, etc). Paper loads this in an isolated early classloader
 * that only sees paper-api plus the plugin jar's own classes.
 *
 * Keep this list in sync with the shadowJar excludes in build.gradle.kts. If
 * you add a runtime dep, exclude it there too — otherwise it ships in the fat
 * jar AND gets downloaded at runtime.
 */
@SuppressWarnings("UnstableApiUsage")
public class EnthusiaMarketPluginLoader implements PluginLoader {

    @Override
    public void classloader(@NotNull PluginClasspathBuilder builder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        resolver.addRepository(
            new RemoteRepository.Builder(
                "central",
                "default",
                "https://repo1.maven.org/maven2/"
            ).build()
        );

        String[] coords = {
            "com.zaxxer:HikariCP:5.1.0",
            "org.xerial:sqlite-jdbc:3.45.1.0",
            "org.mariadb.jdbc:mariadb-java-client:3.3.2",
            "org.slf4j:slf4j-nop:2.0.13",
            "org.jetbrains.kotlin:kotlin-stdlib:2.0.21",
            "org.jetbrains.kotlin:kotlin-reflect:2.0.21",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0",
            "com.charleskorn.kaml:kaml:0.59.0"
        };

        for (String coord : coords) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coord), null));
        }

        builder.addLibrary(resolver);
    }
}
