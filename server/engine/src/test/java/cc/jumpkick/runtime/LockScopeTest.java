// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LockPipelines#lockScope}: every lock entry point (JSONL cascade, HTTP/MCP job) must
 * resolve the same single scope — workspace root with the merged union — so a module-scoped lock
 * can never overwrite the root {@code jk-lock.toml} with one module's closure (JK-1303).
 */
class LockScopeTest {

    private static void workspace(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk = 21
                java = 21

                [workspace]
                modules = ["core", "app"]
                """);
        Files.createDirectories(tmp.resolve("core"));
        Files.writeString(tmp.resolve("core/jk.toml"), """
                [project]
                group = "com.example"
                name  = "core"
                version = "1.0.0"
                jdk = 21
                java = 21

                [dependencies]
                gson = { group = "com.google.code.gson", name = "gson", version = "2.11.0" }
                """);
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.0.0"
                jdk = 21
                java = 21

                [dependencies]
                core = { group = "com.example", name = "core", version = "1.0.0" }
                """);
    }

    private static java.util.List<String> depModules(cc.jumpkick.model.JkBuild build) {
        return build.dependencies().byScope().values().stream()
                .flatMap(java.util.List::stream)
                .map(cc.jumpkick.model.Dependency::module)
                .toList();
    }

    @Test
    void workspace_root_locks_the_merged_union(@TempDir Path tmp) throws Exception {
        workspace(tmp);

        var scope = LockPipelines.lockScope(tmp);

        assertThat(scope.lockDir()).isEqualTo(tmp);
        // The merged model carries a dependency declared only in a member.
        assertThat(depModules(scope.effective())).anyMatch(m -> m.contains("gson"));
    }

    @Test
    void workspace_member_redirects_to_the_root_scope(@TempDir Path tmp) throws Exception {
        workspace(tmp);

        var scope = LockPipelines.lockScope(tmp.resolve("app"));

        // Never the member dir: a module-scoped resolution over the root lock truncates it.
        assertThat(scope.lockDir()).isEqualTo(tmp);
        // And never just the member's closure: the union includes the sibling-only dep.
        assertThat(depModules(scope.effective())).anyMatch(m -> m.contains("gson"));
    }

    @Test
    void standalone_project_locks_itself(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "solo"
                version = "1.0.0"
                jdk = 21
                java = 21
                """);

        var scope = LockPipelines.lockScope(tmp);

        assertThat(scope.lockDir()).isEqualTo(tmp);
    }
}
