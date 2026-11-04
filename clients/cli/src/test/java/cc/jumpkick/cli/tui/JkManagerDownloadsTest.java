// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static cc.jumpkick.cli.tui.JkManagerTestSupport.stream;
import static cc.jumpkick.cli.tui.JkManagerTestSupport.stripAll;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jdk.JdkProgressLabel;
import cc.jumpkick.task.TransferBoard;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The run's downloads at the top of the work tree, in whatever phase they happen. */
class JkManagerDownloadsTest {

    private static JkManager region() {
        var cm = JkManager.plan(stream(new ByteArrayOutputStream()), "Building", false);
        cm.nerdFont = NerdFontCaps.NONE;
        cm.height = 40;
        return cm;
    }

    private static List<TransferBoard.Row> rows(int n) {
        List<TransferBoard.Row> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
            out.add(new TransferBoard.Row("org.example:lib" + i + ":1.0", 512 * 1024, 1024 * 1024));
        return out;
    }

    private static List<String> lines(JkManager cm) {
        return stripAll(cm.renderBuildPlanLines(120, 0));
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(needle)) return i;
        return -1;
    }

    /** A first build downloads its jars while the lock pins them: the rows show under the lock stage. */
    @Test
    void downloads_lead_the_tree_with_a_bar_or_their_bytes_during_the_lock() {
        var cm = region();
        cm.preflight("lock", 0, 0, "Refreshing workspace lock…");
        cm.downloads(new TransferBoard.Snapshot(
                120,
                183,
                118,
                (long) (143.8 * 1024 * 1024),
                2,
                List.of(
                        new TransferBoard.Row("com.google.guava:guava:33.0.0-jre", 1_036_000L, 3_047_503L),
                        new TransferBoard.Row("io.netty:netty-codec:4.1.112", 3L * 1024 * 1024 / 2, -1L))));

        List<String> lines = lines(cm);
        int download = indexOf(lines, "Download › 120/183 · 143.8 MiB");
        assertThat(download).as("the Download row").isPositive();
        int guava = indexOf(lines, "guava");
        assertThat(lines.get(guava)).contains(JdkProgressLabel.bar(33) + "  33% com.google.guava:guava:33.0.0-jre");
        int netty = indexOf(lines, "netty");
        assertThat(lines.get(netty)).contains("1.5 MiB io.netty:netty-codec:4.1.112");
        assertThat(guava).isGreaterThan(download);
        assertThat(netty).isGreaterThan(guava);
        assertThat(indexOf(lines, "Lock ›")).as("the lock row stays").isGreaterThan(netty);
    }

    @Test
    void downloads_no_step_declared_are_counted_in_files() {
        var cm = region();
        cm.downloads(new TransferBoard.Snapshot(0, 0, 3, 5L * 1024 * 1024, 1, rows(1)));

        assertThat(String.join("\n", lines(cm))).contains("Download › 3 files · 5.0 MiB");
    }

    /** A warm sync settles every file from disk and moves no bytes: no Download row flashes. */
    @Test
    void a_run_that_moves_no_bytes_shows_no_download_row() {
        var cm = region();
        cm.preflight("sync", 0, 0, "Syncing dependencies…");
        cm.downloads(new TransferBoard.Snapshot(150, 183, 0, 0L, 0, List.of()));

        assertThat(String.join("\n", lines(cm))).doesNotContain("Download");
    }

    @Test
    void the_row_leaves_when_the_downloads_are_over() {
        var cm = region();
        cm.downloads(new TransferBoard.Snapshot(1, 2, 1, 10L, 1, rows(1)));
        cm.downloads(new TransferBoard.Snapshot(0, 0, 2, 20L, 0, List.of()));

        assertThat(String.join("\n", lines(cm))).doesNotContain("Download").doesNotContain("org.example:lib");
    }

    @Test
    void transfers_past_the_cap_fold_into_a_more_line_and_the_work_row_keeps_its_place() {
        var cm = region();
        cm.preflight("sync", 0, 0, "Syncing dependencies…");
        cm.downloads(new TransferBoard.Snapshot(0, 100, 0, 1L, 20, rows(8)));

        List<String> lines = lines(cm);
        long shown = lines.stream().filter(l -> l.contains("org.example:lib")).count();
        // Download row + transfers + more line + the sync row = the region's row cap.
        assertThat(1 + shown + 1 + 1).isEqualTo(JkManager.MAX_ROWS);
        assertThat(String.join("\n", lines)).contains((20 - shown) + " more in flight");
        assertThat(indexOf(lines, "Sync ›"))
                .as("the work row is not pushed out")
                .isPositive();
    }

    @Test
    void a_short_terminal_keeps_the_region_inside_its_height() {
        var cm = region();
        cm.height = 6;
        cm.preflight("sync", 0, 0, "Syncing dependencies…");
        cm.downloads(new TransferBoard.Snapshot(0, 100, 0, 1L, 8, rows(8)));

        List<String> lines = lines(cm);
        assertThat(lines.size()).isLessThanOrEqualTo(cm.height);
        assertThat(String.join("\n", lines)).contains("Download").contains("more in flight");
    }

    @Test
    void a_settled_region_ignores_a_late_frame() {
        var cm = region();
        cm.dismiss();
        cm.downloads(new TransferBoard.Snapshot(0, 1, 0, 1L, 1, rows(1)));

        assertThat(cm.model.downloads()).isEqualTo(TransferBoard.Snapshot.EMPTY);
    }

    /** A CI log is told when a burst starts and on the heartbeat, never per frame. */
    @Test
    void plain_chrome_says_a_burst_once_then_on_the_heartbeat() {
        var noAnsi = JkConfig.empty().withNoAnsi(true);
        SessionContext.runWhere(Session.defaults().withConfig(noAnsi), () -> {
            var buf = new ByteArrayOutputStream();
            var cm = JkManager.plan(stream(buf), "Build", true);
            cm.downloads(new TransferBoard.Snapshot(3, 183, 3, 2L * 1024 * 1024, 4, rows(2)));
            cm.downloads(new TransferBoard.Snapshot(40, 183, 40, 30L * 1024 * 1024, 6, rows(4)));
            String first = buf.toString(StandardCharsets.UTF_8);
            assertThat(first).contains("Build > downloading 3/183 - 2.0 MiB").doesNotContain("40/183");

            cm.plain.downloadsPrintedNanos -= JkManagerPlainView.HEARTBEAT_MS * 1_000_000L;
            cm.downloads(new TransferBoard.Snapshot(90, 183, 90, 70L * 1024 * 1024, 5, rows(3)));
            assertThat(buf.toString(StandardCharsets.UTF_8)).contains("Build > downloading 90/183 - 70.0 MiB");
            cm.dismiss();
        });
    }
}
