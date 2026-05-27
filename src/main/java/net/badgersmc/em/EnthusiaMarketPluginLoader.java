package net.badgersmc.em;

import net.badgersmc.nexus.paper.loader.NexusPaperPluginLoader;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Paper {@link io.papermc.paper.plugin.loader.PluginLoader} declaring
 * EnthusiaMarket-specific runtime libraries on top of the Nexus stock set
 * provided by {@link NexusPaperPluginLoader}.
 *
 * <p>The Nexus base contributes: kotlin-stdlib, kotlin-reflect,
 * kotlinx-coroutines-core-jvm, kaml-jvm, classgraph, slf4j-api. We add HikariCP
 * + the JDBC drivers + slf4j-nop here.
 *
 * <p>Keep the {@code additionalLibraries()} coordinates in sync with the
 * {@code shadowJar} excludes in build.gradle.kts — anything declared here is
 * resolved at runtime, so it does NOT need to ship in the fat jar.
 */
@SuppressWarnings("UnstableApiUsage")
public class EnthusiaMarketPluginLoader extends NexusPaperPluginLoader {

    @Override
    @NotNull
    protected List<String> additionalLibraries() {
        return List.of(
                "com.zaxxer:HikariCP:5.1.0",
                "org.xerial:sqlite-jdbc:3.45.1.0",
                "org.mariadb.jdbc:mariadb-java-client:3.3.2",
                "org.slf4j:slf4j-nop:2.0.13"
        );
    }
}
