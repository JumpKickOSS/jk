// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk clean} works on each module's own {@code target/}: a full clean removes it, {@code
 * --keep-artifacts} removes the intermediates and keeps the jars at the target root.
 */
class CleanCommandTargetsTest {

    @Test
    void keep_artifacts_removes_module_intermediates_and_keeps_jars(@TempDir Path ws) throws Exception {
        Path appOut = ws.resolve("app/target");
        Files.createDirectories(appOut.resolve("classes/com"));
        Files.writeString(appOut.resolve("classes/com/A.class"), "x");
        Files.createDirectories(appOut.resolve("surefire-reports"));
        Files.writeString(appOut.resolve("surefire-reports/r.xml"), "x");
        Files.writeString(appOut.resolve("app-1.0.0.jar"), "jar-bytes");
        Files.createDirectories(ws.resolve("target/classes"));
        Files.writeString(ws.resolve("target/classes/Root.class"), "x");

        var stats = new PathUtil.Removed();
        PathUtil.deleteTrees(CleanCommand.deleteRoots(ws, List.of(ws, ws.resolve("app")), true), stats);

        assertThat(appOut.resolve("classes")).doesNotExist();
        assertThat(appOut.resolve("surefire-reports")).doesNotExist();
        assertThat(appOut.resolve("app-1.0.0.jar")).exists();
        assertThat(ws.resolve("target/classes")).doesNotExist();
        assertThat(stats.files()).isGreaterThan(0);
    }

    @Test
    void full_clean_removes_every_module_target(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("target/classes"));
        Files.writeString(ws.resolve("target/classes/R.class"), "x");
        Files.createDirectories(ws.resolve("app/target"));
        Files.writeString(ws.resolve("app/target/app.jar"), "x");

        var stats = new PathUtil.Removed();
        PathUtil.deleteTrees(CleanCommand.deleteRoots(ws, List.of(ws, ws.resolve("app")), false), stats);

        assertThat(ws.resolve("target")).doesNotExist();
        assertThat(ws.resolve("app/target")).doesNotExist();
    }
}
