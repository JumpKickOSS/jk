// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Checkout column of {@code jk history list}: present only when one id spans several live checkouts. */
class HistoryCheckoutColumnTest {

    @Test
    void one_live_checkout_per_id_keeps_the_plain_timeline(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("app"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        assertThat(HistoryCommand.checkoutLabels(List.of(entry("p1", a), entry("p1", a), entry("p2", other))))
                .isEmpty();
    }

    @Test
    void two_live_checkouts_of_one_id_label_every_row(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("jk"));
        Path b = Files.createDirectories(tmp.resolve("jk-wt-feature"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        Map<String, String> labels =
                HistoryCommand.checkoutLabels(List.of(entry("p1", a), entry("p1", b), entry("p2", other)));
        assertThat(labels)
                .containsEntry(a.toString(), "jk")
                .containsEntry(b.toString(), "jk-wt-feature")
                .containsEntry(other.toString(), "other");
    }

    @Test
    void checkouts_sharing_a_name_are_labelled_by_their_whole_path(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("one/jk"));
        Path b = Files.createDirectories(tmp.resolve("two/jk"));
        assertThat(HistoryCommand.checkoutLabels(List.of(entry("p1", a), entry("p1", b))))
                .containsEntry(a.toString(), a.toString())
                .containsEntry(b.toString(), b.toString());
    }

    @Test
    void a_deleted_worktree_no_longer_forces_the_column(@TempDir Path tmp) throws Exception {
        Path live = Files.createDirectories(tmp.resolve("jk"));
        Path gone = tmp.resolve("jk-gone");
        assertThat(HistoryCommand.checkoutLabels(List.of(entry("p1", live), entry("p1", gone))))
                .isEmpty();
    }

    private static String entry(String projectId, Path dir) {
        return "{\"type\":\"history-entry\",\"projectId\":\"" + projectId + "\",\"coord\":\"g:n\",\"dir\":\""
                + dir.toString().replace("\\", "\\\\") + "\"}";
    }
}
