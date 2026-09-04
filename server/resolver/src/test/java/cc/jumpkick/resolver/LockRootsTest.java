// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

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
