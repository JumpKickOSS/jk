// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;
import org.junit.jupiter.api.Test;

class DependencyTest {

    @Test
    void path_dep_is_a_pinned_path_source_with_a_placeholder_module() {
        Dependency d = Dependency.pathByName("shared", new PathSource("../shared"));
        assertThat(d.isPath()).isTrue();
        assertThat(d.isGit()).isFalse();
        assertThat(d.isFile()).isFalse();
        assertThat(Objects.requireNonNull(d.pathSource()).rawPath()).isEqualTo("../shared");
        assertThat(d.module()).isEqualTo("path:shared");
        assertThat(d.pinned()).isTrue();
    }

    @Test
    void a_dependency_cannot_be_both_git_and_path() {
        GitSource git = GitSource.of("https://x/y", "https://x/y", new GitRefSpec.Tag("v1"));
        assertThatThrownBy(() -> new Dependency(
                        "d", "g:a", VersionSelector.parse("=1"), git, null, true, false, new PathSource("../p")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void plain_coordinate_deps_carry_no_path_source() {
        Dependency d = Dependency.of("junit", "org.junit:junit", VersionSelector.parse("=5.0"));
        assertThat(d.isPath()).isFalse();
        assertThat(d.pathSource()).isNull();
    }

    @Test
    void workspace_dep_defaults_to_main_kind() {
        Dependency d = Dependency.workspace("transport");
        assertThat(d.isWorkspace()).isTrue();
        assertThat(d.kind()).isEqualTo(DependencyKind.MAIN);
        assertThat(d.isTestsKind()).isFalse();
    }

    @Test
    void workspace_dep_can_select_tests_kind() {
        Dependency d = Dependency.workspace("transport", DependencyKind.TESTS);
        assertThat(d.isTestsKind()).isTrue();
        assertThat(d.kind()).isEqualTo(DependencyKind.TESTS);
    }

    @Test
    void workspace_dep_can_select_fixtures_independently_of_kind() {
        Dependency d = Dependency.workspace("transport").withFixtures(true);
        assertThat(d.isFixtures()).isTrue();
        assertThat(d.isTestsKind()).isFalse();
        assertThat(d.withKind(DependencyKind.TESTS).isFixtures()).isTrue();
    }

    @Test
    void package_key_maps_external_tests_kind_to_test_jar() {
        Dependency main = Dependency.of("lib", "com.acme:lib", VersionSelector.parse("=1.0.0"));
        assertThat(main.packageKey()).isEqualTo("com.acme:lib:jar:");

        Dependency tests = main.withKind(DependencyKind.TESTS);
        assertThat(tests.packageKey()).isEqualTo("com.acme:lib:test-jar:tests");
    }

    @Test
    void package_key_carries_the_classifier_and_the_plain_jar_stays_distinct() {
        Dependency plain = Dependency.of("lwjgl", "org.lwjgl:lwjgl", VersionSelector.parse("=3.3.6"));
        Dependency natives = plain.withClassifier("natives-linux");
        assertThat(natives.classifier()).isEqualTo("natives-linux");
        assertThat(natives.packageKey()).isEqualTo("org.lwjgl:lwjgl:jar:natives-linux");
        assertThat(natives.module()).isEqualTo(plain.module());
        assertThat(natives.withClassifier(null).packageKey()).isEqualTo(plain.packageKey());
        assertThat(natives.withOptional(true).classifier()).isEqualTo("natives-linux");
    }

    @Test
    void a_blank_or_colon_classifier_is_refused() {
        Dependency plain = Dependency.of("lwjgl", "org.lwjgl:lwjgl", VersionSelector.parse("=3.3.6"));
        assertThatThrownBy(() -> plain.withClassifier(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plain.withClassifier("a:b")).isInstanceOf(IllegalArgumentException.class);
    }
}
