// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.build.TaskExec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The augment runs in a grandchild JVM, so {@code --offline} can only reach it as an argument.
 *
 * <p>Before this, it could not reach it at all: the augment read {@code jk.quarkus.offline}, a
 * system property with exactly one occurrence in the tree — its own read. Nothing set it, so the
 * {@code setOffline(true)} arm it guarded was dead code and a Quarkus module's augment resolved
 * through Aether against Central on every offline build. The two ends of this vector are pinned
 * together on purpose: {@code QuarkusAugmentMain.main} rejects anything but ten arguments, so a
 * drift on either side is a startup failure rather than a silently dropped policy.
 */
class QuarkusAugmentArgsTest {

    @Test
    void the_offline_decision_is_the_last_argument_the_augment_is_given() {
        assertThat(argsFor(true)).last().isEqualTo("true");
        assertThat(argsFor(false)).last().isEqualTo("false");
    }

    @Test
    void the_vector_is_the_arity_the_augment_requires() {
        // QuarkusAugmentMain.main exits USAGE on anything but 10.
        assertThat(argsFor(false)).hasSize(10);
    }

    /** The rest of the vector is unchanged by the policy — only the last element moves. */
    @Test
    void nothing_else_in_the_vector_depends_on_it() {
        List<String> offline = argsFor(true);
        List<String> online = argsFor(false);
        assertThat(offline.subList(0, offline.size() - 1)).isEqualTo(online.subList(0, online.size() - 1));
        assertThat(offline).element(4).isEqualTo("com.example");
    }

    private static List<String> argsFor(boolean offline) {
        return QuarkusPlugin.augmentArgs(
                new ProbeExec(offline),
                Path.of("/m/target/classes/main"),
                Path.of("/m/target/quarkus-app"),
                "widget",
                Path.of("/m/target/runtime-jars.tsv"),
                "3.38.3");
    }

    /** Only the three accessors {@code augmentArgs} reads; anything else is not part of the vector. */
    private record ProbeExec(boolean offline) implements TaskExec {
        @Override
        public Path moduleDir() {
            return Path.of("/m");
        }

        @Override
        public ProjectFacts project() {
            return new ProjectFacts("com.example", "widget", "1.2.3", 25, null, false, false, Map.of());
        }

        @Override
        public Path classesDir() {
            return Path.of("/m/target/classes/main");
        }

        @Override
        public List<Path> runtimeClasspath() {
            return List.of();
        }

        @Override
        public List<PackageIo.RuntimeEntry> runtimeEntries() {
            return List.of();
        }

        @Override
        public PluginConfig config() {
            return new PluginConfig("quarkus", Map.of());
        }

        @Override
        public Path scratch() {
            return Path.of("/m/target");
        }

        @Override
        public Optional<Path> extra(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<Path> stepOutput(String step) {
            return Optional.empty();
        }

        @Override
        public Path javaHome() {
            return Path.of("/jdk");
        }

        @Override
        public void label(String text) {
            // the vector does not depend on progress reporting
        }
    }
}
