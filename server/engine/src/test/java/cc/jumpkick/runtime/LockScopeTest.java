// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LockPlans#lockScope}: every lock entry point (JSONL cascade, HTTP/MCP job) must
 * resolve the same single scope — workspace root with the merged union — so a module-scoped lock
 * can never overwrite the root {@code jk-lock.toml} with one module's closure.
 */
class LockScopeTest {

    private static void workspace(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        Files.createDirectories(tmp.resolve("core"));
        Files.writeString(tmp.resolve("core/jk.toml"), """
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies]
                gson = { group = "com.google.code.gson", name = "gson", version = "2.11.0" }
                """);
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/jk.toml"), """
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 25
                java = 25

                [dependencies]
                core = { group = "com.example", name = "core", version = "1.0.0" }
                """);
    }

    private static List<String> depModules(JkBuild build) {
        return build.dependencies().byScope().values().stream()
                .flatMap(List::stream)
                .map(Dependency::module)
                .toList();
    }

    @Test
    void workspace_root_locks_the_merged_union(@TempDir Path tmp) throws Exception {
        workspace(tmp);

        var scope = LockPlans.lockScope(tmp);

        assertThat(scope.lockDir()).isEqualTo(tmp);
        // The merged model carries a dependency declared only in a member.
        assertThat(depModules(scope.effective())).anyMatch(m -> m.contains("gson"));
    }

    @Test
    void workspace_member_redirects_to_the_root_scope(@TempDir Path tmp) throws Exception {
        workspace(tmp);

        var scope = LockPlans.lockScope(tmp.resolve("app"));

        // Never the member dir: a module-scoped resolution over the root lock truncates it.
        assertThat(scope.lockDir()).isEqualTo(tmp);
        // And never just the member's closure: the union includes the sibling-only dep.
        assertThat(depModules(scope.effective())).anyMatch(m -> m.contains("gson"));
    }

    @Test
    void a_member_pinned_at_unresolved_is_refused_at_its_manifest_line(@TempDir Path tmp) throws Exception {
        workspace(tmp);
        Files.writeString(tmp.resolve("core/jk.toml"), """
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                java = 25

                [dependencies]
                gson = { group = "com.google.code.gson", name = "gson", version = "2.11.0" }

                [platform-dependencies]
                netty-bom = { group = "io.netty", version = "unresolved" }
                """);

        assertThatThrownBy(() -> LockPlans.lockScope(tmp))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining(tmp.resolve("core/jk.toml") + ":10 [platform-dependencies] netty-bom")
                .hasMessageContaining("io.netty:netty-bom is pinned at `unresolved`");
    }

    @Test
    void a_standalone_project_pinned_at_unresolved_is_refused_before_it_resolves(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "solo"
                version = "1.0.0"
                java = 25

                [dependencies]
                guava = { group = "com.google.guava", version = "unresolved" }
                """);

        assertThatThrownBy(() -> LockPlans.lockScope(tmp))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining(tmp.resolve("jk.toml") + ":7 [dependencies] guava");
    }

    @Test
    void standalone_project_locks_itself(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "solo"
                version = "1.0.0"
                jdk = 25
                java = 25
                """);

        var scope = LockPlans.lockScope(tmp);

        assertThat(scope.lockDir()).isEqualTo(tmp);
    }
}
