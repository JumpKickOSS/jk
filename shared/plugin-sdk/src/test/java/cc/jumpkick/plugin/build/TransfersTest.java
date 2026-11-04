// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransfersTest {

    private final List<String> replies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void install() {
        Transfers.install(replies::add);
    }

    @AfterEach
    void uninstall() {
        Transfers.install(null);
    }

    private List<String> events() {
        return replies.stream().map(r -> Jsonl.str(r, "e")).toList();
    }

    @Test
    void a_body_reports_its_start_progress_and_end() {
        try (Transfers.Transfer t = Transfers.open("g:a:1", true)) {
            t.started(10);
            t.advanced(10);
            t.finished();
        }

        assertThat(events()).containsExactly("start", "read", "done");
        assertThat(Jsonl.str(replies.get(0), "label")).isEqualTo("g:a:1");
        assertThat(Jsonl.bool(replies.get(0), "artifact", false)).isTrue();
        assertThat(Jsonl.longValue(replies.get(1), "read", -1)).isEqualTo(10);
        assertThat(replies).allMatch(r -> "transfer".equals(Jsonl.str(r, "t")));
    }

    @Test
    void small_reads_are_reported_sparsely_and_the_last_count_always() {
        try (Transfers.Transfer t = Transfers.open("big")) {
            t.started(100_000);
            for (int read = 1; read <= 100_000; read++) t.advanced(read);
            t.finished();
        }

        long reads = events().stream().filter("read"::equals).count();
        assertThat(reads).as("100,000 calls, a few reports").isLessThan(10);
        String last = replies.get(replies.size() - 2);
        assertThat(Jsonl.longValue(last, "read", -1))
                .as("the final count before done")
                .isEqualTo(100_000);
    }

    @Test
    void a_body_that_fails_is_closed_not_done() {
        try (Transfers.Transfer t = Transfers.open("x")) {
            t.started(-1);
            t.advanced(5);
        }

        assertThat(events()).last().isEqualTo("close");
    }

    @Test
    void a_forked_jvms_report_is_read_by_the_same_marker_it_is_written_with() {
        assertThat(TaskExec.ToolRun.TRANSFER_PREFIX).isEqualTo(Transfers.FORKED_PREFIX);
        String json = "{\"t\":\"transfer\",\"id\":\"9:1\",\"e\":\"done\"}";

        Transfers.forward(json);

        assertThat(replies).containsExactly(json);
    }

    @Test
    void copy_streams_a_body_as_one_transfer(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("p.hpi");
        long n = Transfers.copy(new ByteArrayInputStream(new byte[300_000]), target, "g:p:1!hpi", 300_000);

        assertThat(n).isEqualTo(300_000);
        assertThat(Files.size(target)).isEqualTo(300_000);
        assertThat(events()).first().isEqualTo("start");
        assertThat(events()).last().isEqualTo("done");
    }
}
