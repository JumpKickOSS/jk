// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The partition is data over the manifest: it needs no repository and no solver to be asserted. */
class LockRootsTest {

    private static final String MANIFEST = """
            group = "com.example"
            name = "app"
            version = "1.0.0"

            [dependencies]
            core = { group = "com.foo", name = "core", version = "1.0" }
            mysql = { group = "com.foo", name = "mysql", version = "1.0", optional = true }

            [test-dependencies]
            truth = { group = "com.foo", name = "truth", version = "1.0" }

            [features]
            default = []
            db = { deps = ["mysql"] }
            """;

    @Test
    void declared_roots_land_in_their_graph_and_an_optional_waits_for_its_feature() throws Exception {
        JkBuild project = JkBuildParser.parse(MANIFEST);

        LockRoots.Declared plain = LockRoots.partition(project, List.of(), true);
        assertThat(plain.main().keySet()).containsExactly("com.foo:core:jar:");
        assertThat(plain.test().keySet()).containsExactly("com.foo:truth:jar:", LockRoots.JUNIT_LAUNCHER.packageKey());
        assertThat(plain.processor()).isEmpty();

        LockRoots.Declared withDb = LockRoots.partition(project, List.of("db"), true);
        assertThat(withDb.main().keySet()).containsExactly("com.foo:core:jar:", "com.foo:mysql:jar:");
    }

    @Test
    void junit_jupiter_is_injected_only_when_the_user_declared_no_test_dependencies() throws Exception {
        JkBuild bare = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0.0"
                """);
        LockRoots.Declared declared = LockRoots.partition(bare, List.of(), true);
        assertThat(declared.test().keySet())
                .containsExactly(LockRoots.JUNIT_LAUNCHER.packageKey(), LockRoots.JUNIT_JUPITER.packageKey());
        assertThat(declared.test().values())
                .allMatch(d -> "latest".equals(d.version().raw()));
    }

    @Test
    void a_declared_junit4_brings_the_vintage_engine_into_the_test_graph() throws Exception {
        JkBuild junit4 = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [test-dependencies]
                junit = { group = "junit", name = "junit", version = "4.13.2" }
                """);
        LockRoots.Declared declared = LockRoots.partition(junit4, List.of(), true);
        assertThat(declared.test().keySet())
                .containsExactly(
                        "junit:junit:jar:",
                        LockRoots.JUNIT_LAUNCHER.packageKey(),
                        TestEngines.JUNIT4.engine().packageKey());
        assertThat(requireNonNull(
                                declared.test().get(TestEngines.JUNIT4.engine().packageKey()))
                        .version()
                        .raw())
                .as("the engine rides the launcher's selector so both land on one Platform line")
                .isEqualTo(LockRoots.JUNIT_LAUNCHER.version().raw());
        assertThat(TestEngines.declaredTriggerPins(junit4)).containsExactly(Map.entry("junit:junit", "4.13.2"));

        JkBuild jupiterOnly = JkBuildParser.parse(MANIFEST);
        assertThat(LockRoots.partition(jupiterOnly, List.of(), true).test().keySet())
                .doesNotContain(TestEngines.JUNIT4.engine().packageKey());
        assertThat(TestEngines.declaredTriggerPins(jupiterOnly)).isEmpty();
    }

    @Test
    void a_declared_vintage_engine_is_the_users_and_a_floating_junit4_pins_nothing() throws Exception {
        JkBuild own = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [test-dependencies]
                junit          = { group = "junit", name = "junit", version = "^4.12" }
                vintage-engine = { group = "org.junit.vintage", name = "junit-vintage-engine", version = "6.1.3" }
                """);
        LockRoots.Declared declared = LockRoots.partition(own, List.of(), true);
        assertThat(requireNonNull(
                                declared.test().get(TestEngines.JUNIT4.engine().packageKey()))
                        .version()
                        .raw())
                .isEqualTo("6.1.3");
        assertThat(TestEngines.declaredTriggerPins(own)).isEmpty();
    }

    @Test
    void a_junit4_pin_below_the_vintage_floor_is_refused_in_one_sentence() throws Exception {
        JkBuild junit3 = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [test-dependencies]
                junit = { group = "junit", name = "junit", version = "3.8.2" }
                """);
        assertThatThrownBy(() -> LockRoots.partition(junit3, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("[test-dependencies] junit:junit 3.8.2 cannot run under jk: its suites run through"
                        + " org.junit.vintage:junit-vintage-engine, which needs junit:junit 4.12 or later"
                        + " — raise the pin to 4.13.2");
    }

    @Test
    void a_feature_naming_a_non_optional_dependency_is_refused_in_one_sentence() throws Exception {
        JkBuild project = JkBuildParser.parse("""
                group = "com.example"
                name = "app"
                version = "1.0.0"

                [dependencies]
                core = { group = "com.foo", name = "core", version = "1.0" }

                [features]
                default = []
                fast = { deps = ["core"] }
                """);
        assertThatThrownBy(() -> LockRoots.partition(project, List.of("fast"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("feature dependency 'core' is not a declared optional dependency"
                        + " — declare it under [dependencies.*] with `optional = true`");
    }

    @Test
    void split_moves_file_dependencies_out_of_every_graph_once() {
        Dependency jar = new Dependency("com.foo:core", VersionSelector.parse("=1.0"));
        Dependency local = Dependency.file("thing", "com.local:thing", "1.0", "ab".repeat(32));
        LinkedHashMap<String, Dependency> main =
                new LinkedHashMap<>(Map.of(jar.packageKey(), jar, local.packageKey(), local));
        LinkedHashMap<String, Dependency> test = new LinkedHashMap<>(Map.of(local.packageKey(), local));

        LockRoots.Roots roots = new LockRoots.Declared(main, test, new LinkedHashMap<>()).split();

        assertThat(roots.main()).containsExactly(jar);
        assertThat(roots.test()).isEmpty();
        assertThat(roots.fileDeps()).containsExactly(local);
        assertThat(roots.declaredCount()).isEqualTo(2);
    }
}
