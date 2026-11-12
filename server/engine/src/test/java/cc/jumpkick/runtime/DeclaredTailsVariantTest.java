// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The declared tails read the manifest the plan was built from: a {@code --variant} whose overlay
 * makes a plugin's packaging runnable turns a library's sources jar off.
 */
class DeclaredTailsVariantTest {

    @TempDir
    Path tmp;

    private PluginDescriptor plugin;

    @BeforeEach
    void register() {
        plugin = PluginDescriptors.parse("""
                [plugin]
                id = "tails-variant"
                table = "tails-variant"

                [packaging]
                exec-mode = "none"

                [[packaging.variant]]
                when = { config = "build-type", equals = "release" }
                exec-mode = "jar"
                """, "tails-variant.toml");
        PluginTableRegistry.putBuiltIn(plugin, null);
    }

    @AfterEach
    void unregister() {
        PluginTableRegistry.unregister(plugin);
    }

    @Test
    void the_default_variant_ships_a_library_s_sources_jar() throws Exception {
        assertThat(plannedSteps("")).contains(TaskNames.PACKAGE_SOURCES);
    }

    @Test
    void a_variant_that_makes_the_module_runnable_drops_the_sources_jar() throws Exception {
        assertThat(plannedSteps("release")).contains(TaskNames.PACKAGE_JAR).doesNotContain(TaskNames.PACKAGE_SOURCES);
    }

    private Set<String> plannedSteps(String variant) throws Exception {
        Path dir = tmp.resolve("lib");
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(dir.resolve("src/main/java/ex/Lib.java"), "package ex; public class Lib {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "lib"
                version = "1.0"
                java = 25

                [tails-variant]
                """);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(dir.resolve("jk-lock.toml"), "schema = 1\n");
        BuildPlanner.Inputs inputs =
                TestInputs.of(dir, cache).withSkipTests(true).withVariant(variant);
        BuildPlan.Builder b = BuildPlanner.coreBuilder(inputs, true);
        PlannerTails.appendDeclaredTails(b, inputs);
        return b.build().steps().stream().map(Task::name).collect(Collectors.toSet());
    }
}
