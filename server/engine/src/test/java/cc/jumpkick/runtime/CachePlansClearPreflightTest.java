// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A forced clear drops both preflight memo copies: the in-tree one and the durable one the load prefers. */
class CachePlansClearPreflightTest {

    @Test
    void clear_removes_the_in_tree_and_the_durable_preflight_memo(@TempDir Path tmp) throws IOException {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), "name = \"app\"\n");
        Path local = project.resolve(BuildLayout.TARGET)
                .resolve(".jk")
                .resolve("preflight")
                .resolve("dirty-memo.txt");
        Files.createDirectories(local.getParent());
        Files.writeString(local, "memo");

        SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
            try {
                Path durable = PreflightMemo.durableMemoFile(project);
                Files.createDirectories(durable.getParent());
                Files.writeString(durable, "memo");

                assertThat(CachePlans.clearBuildPlan(cache, project, false)
                                .run()
                                .success())
                        .isTrue();

                assertThat(local).doesNotExist();
                assertThat(durable).doesNotExist();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
