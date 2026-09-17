// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.build.TaskExec;
import cc.jumpkick.testing.ShortTempDirs;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The augment runs in a grandchild JVM, so the engine's per-job decisions — {@code --offline} and
 * the platform-properties path the engine fetched — can only reach it as arguments. The two ends
 * of this vector are pinned together on purpose: {@code QuarkusAugmentMain.main} rejects anything
 * but eleven arguments, so a drift on either side is a startup failure rather than a silently
 * dropped policy.
 */
class QuarkusAugmentArgsTest {

    private static final Path MODULE = ShortTempDirs.path().resolve("m");
    private static final Path TEST_MODEL = MODULE.resolve("target/plugin/quarkus-test-model/test-model");
    private static final Path PROPS =
            ShortTempDirs.path().resolve("store/cas/quarkus-bom-quarkus-platform-properties-3.38.3.properties");

    @Test
    void the_offline_decision_is_the_last_argument_the_augment_is_given() {
        assertThat(argsFor(true)).last().isEqualTo("true");
        assertThat(argsFor(false)).last().isEqualTo("false");
    }

    @Test
    void the_vector_is_the_arity_the_augment_requires() {
        // QuarkusAugmentMain.main exits USAGE on anything but 11.
        assertThat(argsFor(false)).hasSize(11);
    }

    /**
     * The platform-properties path rides the vector, sourced from the engine-supplied
     * step-dependency — the augment never resolves the coordinate itself.
     */
    @Test
    void the_platform_properties_path_is_the_engine_supplied_extra() {
        assertThat(argsFor(false)).element(9).isEqualTo(PROPS.toString());
    }

    /** A launch without the fetched artifact fails naming the step-dependency, not silently. */
    @Test
    void a_missing_platform_properties_extra_refuses_to_build_the_vector() {
        assertThatThrownBy(() -> QuarkusPlugin.augmentArgs(
                        new ProbeExec(false, null),
                        MODULE.resolve("target/classes/main"),
                        MODULE.resolve("target/quarkus-app"),
                        "widget",
                        MODULE.resolve("target/runtime-jars.tsv"),
                        "3.38.3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QuarkusPlugin.PLATFORM_PROPS_EXTRA);
    }

    /** The rest of the vector is unchanged by the policy — only the last element moves. */
    @Test
    void nothing_else_in_the_vector_depends_on_it() {
        List<String> offline = argsFor(true);
        List<String> online = argsFor(false);
        assertThat(offline.subList(0, offline.size() - 1)).isEqualTo(online.subList(0, online.size() - 1));
        assertThat(offline).element(4).isEqualTo("com.example");
    }

    /**
     * The test-model fork shares the shape: the engine's offline decision last, the platform
     * properties path from the step-dependency, and the arity {@code QuarkusTestModelMain.main}
     * requires.
     */
    @Test
    void the_test_model_vector_carries_the_same_decisions() {
        List<String> offline = testModelArgsFor(true);
        List<String> online = testModelArgsFor(false);
        assertThat(offline).hasSize(10).last().isEqualTo("true");
        assertThat(online).last().isEqualTo("false");
        assertThat(offline).element(8).isEqualTo(PROPS.toString());
        assertThat(offline).element(2).isEqualTo(TEST_MODEL.toString());
        assertThat(offline.subList(0, offline.size() - 1)).isEqualTo(online.subList(0, online.size() - 1));
    }

    /** The fork's arguments name the model and the metaspace the bootstrap's resident applications need. */
    @Test
    void the_test_jvm_arguments_name_the_model_and_the_metaspace_cap() {
        Path model = TEST_MODEL.resolve("test-app-model.json");
        assertThat(QuarkusPlugin.testJvmArgs(model))
                .containsExactly(
                        "-Dquarkus-internal-test.serialized-app-model.path=" + model, "-XX:MaxMetaspaceSize=1g");
    }

    private static List<String> testModelArgsFor(boolean offline) {
        return QuarkusPlugin.testModelArgs(
                new ProbeExec(offline, PROPS),
                MODULE.resolve("target/classes/main"),
                TEST_MODEL,
                MODULE.resolve("target/test-runtime-jars.tsv"),
                "3.38.3");
    }

    private static List<String> argsFor(boolean offline) {
        return QuarkusPlugin.augmentArgs(
                new ProbeExec(offline, PROPS),
                MODULE.resolve("target/classes/main"),
                MODULE.resolve("target/quarkus-app"),
                "widget",
                MODULE.resolve("target/runtime-jars.tsv"),
                "3.38.3");
    }

    /** Only the four accessors {@code augmentArgs} reads; anything else is not part of the vector. */
    private record ProbeExec(boolean offline, @Nullable Path platformProps) implements TaskExec {
        @Override
        public Path moduleDir() {
            return MODULE;
        }

        @Override
        public ProjectFacts project() {
            return new ProjectFacts("com.example", "widget", "1.2.3", 25, null, false, false, Map.of());
        }

        @Override
        public Path classesDir() {
            return MODULE.resolve("target/classes/main");
        }

        @Override
        public List<Path> runtimeClasspath() {
            return List.of();
        }

        @Override
        public List<Path> compileClasspath() {
            return List.of();
        }

        @Override
        public List<Path> siblingFiles(String configKey) {
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
            return MODULE.resolve("target");
        }

        @Override
        public Optional<Path> extra(String name) {
            return QuarkusPlugin.PLATFORM_PROPS_EXTRA.equals(name)
                    ? Optional.ofNullable(platformProps)
                    : Optional.empty();
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

        @Override
        public void diagnostic(String severity, @Nullable String file, int line, int col, String message) {
            // nor on findings
        }
    }
}
