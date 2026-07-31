// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1310: workspace member outputs live under {@code <workspace>/target/<rel>/} (Mill-style
 * layout), so {@code jk clean --keep-artifacts} must sweep intermediates there — the pre-layout
 * per-module {@code <member>/target/} sweep removed nothing.
 */
class CleanCommandTargetsTest {

    @Test
    void keep_artifacts_removes_member_intermediates_and_keeps_jars(@TempDir Path ws) throws Exception {
        Path appOut = ws.resolve("target/app");
        Files.createDirectories(appOut.resolve("classes/com"));
        Files.writeString(appOut.resolve("classes/com/A.class"), "x");
        Files.createDirectories(appOut.resolve("test-results"));
        Files.writeString(appOut.resolve("test-results/r.xml"), "x");
        Files.writeString(appOut.resolve("app-1.0.0.jar"), "jar-bytes");
        // Root's own intermediates sit directly under <ws>/target/.
        Files.createDirectories(ws.resolve("target/classes"));
        Files.writeString(ws.resolve("target/classes/Root.class"), "x");

        long[] stats = {0, 0};
        CleanCommand.cleanTargets(ws, List.of(ws, ws.resolve("app")), true, stats);

        assertThat(Files.exists(appOut.resolve("classes"))).isFalse();
        assertThat(Files.exists(appOut.resolve("test-results"))).isFalse();
        assertThat(Files.exists(appOut.resolve("app-1.0.0.jar"))).isTrue(); // artifact kept
        assertThat(Files.exists(ws.resolve("target/classes"))).isFalse();
        assertThat(stats[0]).isGreaterThan(0);
    }

    @Test
    void keep_artifacts_still_sweeps_a_pre_layout_member_target(@TempDir Path ws) throws Exception {
        Path legacy = ws.resolve("app/target");
        Files.createDirectories(legacy.resolve("classes"));
        Files.writeString(legacy.resolve("classes/Old.class"), "x");
        Files.writeString(legacy.resolve("app.jar"), "jar-bytes");

        long[] stats = {0, 0};
        CleanCommand.cleanTargets(ws, List.of(ws, ws.resolve("app")), true, stats);

        assertThat(Files.exists(legacy.resolve("classes"))).isFalse();
        assertThat(Files.exists(legacy.resolve("app.jar"))).isTrue();
    }

    @Test
    void full_clean_removes_both_layout_and_legacy_targets(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("target/app/classes"));
        Files.writeString(ws.resolve("target/app/classes/A.class"), "x");
        Files.createDirectories(ws.resolve("app/target"));
        Files.writeString(ws.resolve("app/target/old.jar"), "x");

        long[] stats = {0, 0};
        CleanCommand.cleanTargets(ws, List.of(ws, ws.resolve("app")), false, stats);

        assertThat(Files.exists(ws.resolve("target"))).isFalse();
        assertThat(Files.exists(ws.resolve("app/target"))).isFalse();
    }
}
