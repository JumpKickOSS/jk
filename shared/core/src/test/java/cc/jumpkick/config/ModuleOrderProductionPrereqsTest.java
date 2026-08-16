// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Production-scope prereqs omit test/dev workspace siblings. Used by {@code jk native
 * --skip-tests}; with tests enabled, the native cascade uses all scopes so a dirty
 * test harness is rebuilt before tests run against it.
 */
class ModuleOrderProductionPrereqsTest {

    @TempDir
    Path tmp;

    @Test
    void production_scopes_exclude_test_workspace_siblings() throws Exception {
        Path core = module("jk-core", "core", """
                [dependencies]
                """);
        Path engine = module("jk-engine", "engine", """
                [dependencies]
                jk-core = { workspace = true }
                """);
        Path cli = module("jk-cli", "cli", """
                [dependencies]
                jk-core = { workspace = true }

                [test-dependencies]
                jk-engine = { workspace = true }
                """);

        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(core, JkBuildParser.parse(core.resolve("jk.toml")));
        modules.put(engine, JkBuildParser.parse(engine.resolve("jk.toml")));
        modules.put(cli, JkBuildParser.parse(cli.resolve("jk.toml")));

        Map<String, Path> byCoord = new LinkedHashMap<>();
        Map<String, Path> byName = new LinkedHashMap<>();
        for (var e : modules.entrySet()) {
            byCoord.put(
                    e.getValue().project().group() + ":"
                            + e.getValue().project().name(),
                    e.getKey());
            byName.put(e.getValue().project().name(), e.getKey());
        }

        Set<Path> all = ModuleOrder.modulePrereqs(cli, modules.get(cli), byCoord, byName);
        assertThat(all).containsExactlyInAnyOrder(core, engine);

        Set<Path> prod =
                ModuleOrder.modulePrereqs(cli, modules.get(cli), byCoord, byName, ModuleOrder.PRODUCTION_SCOPES);
        assertThat(prod).containsExactly(core);
        assertThat(prod).doesNotContain(engine);
        assertThat(ModuleOrder.PRODUCTION_SCOPES).doesNotContain(Scope.TEST, Scope.DEV, Scope.TEST_DEV);
    }

    private Path module(String name, String dirName, String extra) throws Exception {
        Path dir = tmp.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "%s"
                version = "1.0"
                java = 25
                %s
                """.formatted(name, extra));
        return dir;
    }
}
