// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.BuildLogicAnchor;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one classification of which stems a directory's {@code .jk/} may carry. */
class BuildLogicScopeTest {

    @Test
    void a_lone_manifest_is_standalone_and_takes_module_stems_and_guard(@TempDir Path dir) throws Exception {
        Path project = manifest(dir.resolve("proj"), "");
        Files.createDirectories(project.resolve("src/main/java"));

        BuildLogicScope scope = BuildLogicScope.of(project);
        assertThat(scope).isEqualTo(BuildLogicScope.STANDALONE);
        assertThat(scope.accepts(BuildLogicAnchor.AFTER_RESOURCES)).isTrue();
        assertThat(scope.accepts(BuildLogicAnchor.BEFORE_COMPILE)).isTrue();
        assertThat(scope.accepts(BuildLogicAnchor.GUARD)).isTrue();
        assertThat(scope.accepts(BuildLogicAnchor.AFTER_BUILD))
                .as("a standalone plan never reaches after-build")
                .isFalse();
    }

    @Test
    void a_sourceless_workspace_root_takes_root_stems_only(@TempDir Path dir) throws Exception {
        Path root = manifest(dir.resolve("ws"), "[workspace]\nmodules = [\"core\"]\n");

        BuildLogicScope scope = BuildLogicScope.of(root);
        assertThat(scope).isEqualTo(BuildLogicScope.WORKSPACE_ROOT);
        assertThat(scope.accepts(BuildLogicAnchor.AFTER_BUILD)).isTrue();
        assertThat(scope.accepts(BuildLogicAnchor.GUARD)).isTrue();
        assertThat(scope.accepts(BuildLogicAnchor.AFTER_RESOURCES)).isFalse();
    }

    @Test
    void an_empty_workspace_table_still_makes_a_root(@TempDir Path dir) throws Exception {
        Path root = manifest(dir.resolve("ws"), "[workspace]\nmodules = []\n");
        assertThat(BuildLogicScope.of(root)).isEqualTo(BuildLogicScope.WORKSPACE_ROOT);
    }

    @Test
    void a_root_with_its_own_sources_is_a_module_as_well(@TempDir Path dir) throws Exception {
        Path root = manifest(dir.resolve("ws"), "[workspace]\nmodules = [\"core\"]\n");
        Files.createDirectories(root.resolve("src/main/java"));
        assertThat(BuildLogicScope.of(root)).isEqualTo(BuildLogicScope.STANDALONE);
    }

    @Test
    void a_listed_module_is_a_member_and_takes_module_stems_only(@TempDir Path dir) throws Exception {
        Path root = manifest(dir.resolve("ws"), "[workspace]\nmodules = [\"core\"]\n");
        Path core = manifest(root.resolve("core"), "");

        BuildLogicScope scope = BuildLogicScope.of(core);
        assertThat(scope).isEqualTo(BuildLogicScope.MEMBER);
        assertThat(scope.accepts(BuildLogicAnchor.BEFORE_PACKAGE)).isTrue();
        assertThat(scope.accepts(BuildLogicAnchor.GUARD)).isFalse();
        assertThat(scope.accepts(BuildLogicAnchor.AFTER_BUILD)).isFalse();
    }

    private static Path manifest(Path dir, String tail) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "%s"
                version = "0.0.1"
                jdk = 25

                """.formatted(dir.getFileName()) + tail);
        return dir;
    }
}
