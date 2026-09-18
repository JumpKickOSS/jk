// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.task.IoLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Indexing a workspace's siblings is linear in the workspace: the root is known, so no unit
 * re-locates it by walking its ancestors and scanning the root's module list. A forecast resolves
 * every module's classpath in turn, so a per-unit walk made the forecast quadratic in the module
 * count — minutes on a thousand-module reactor.
 */
class WorkspaceClasspathSiblingIndexTest {

    private static final int MODULES = 40;

    @Test
    void resolving_every_module_scans_a_linear_number_of_files(@TempDir Path root) throws Exception {
        StringBuilder modules = new StringBuilder();
        for (int i = 0; i < MODULES; i++)
            modules.append(i == 0 ? "" : ", ").append("\"m").append(i).append('"');
        aged(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(modules));
        for (int i = 0; i < MODULES; i++) {
            Path dir = Files.createDirectories(root.resolve("m" + i));
            String deps = i == 0 ? "" : "[dependencies]\nm0 = { workspace = true }\n";
            aged(dir.resolve("jk.toml"), """
                    group = "com.ex"
                    name = "m%d"
                    version = "0.1.0"
                    java = 25

                    %s""".formatted(i, deps));
        }
        // One request, as a forecast is: the module list is loaded once and shared.
        IoLedger ledger = new IoLedger();
        IoLedger.open(ledger);
        try {
            TomlScan.clearCache();
            for (int i = 0; i < MODULES; i++) {
                Path dir = root.resolve("m" + i);
                JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
                WorkspaceClasspath.resolve(dir, build, Set.of(Scope.MAIN));
            }
            long scans = TomlScan.scans();
            // A per-unit root walk scans the root once per unit per module: MODULES squared on top
            // of the linear share, which is a few scans per module.
            assertThat(scans)
                    .as("TOML scans for resolving every one of %d modules", MODULES)
                    .isLessThan((long) MODULES * MODULES / 2);
        } finally {
            RequestScope.release();
            IoLedger.close();
        }
    }

    private static void aged(Path file, String body) throws IOException {
        Files.writeString(file, body);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
    }
}
