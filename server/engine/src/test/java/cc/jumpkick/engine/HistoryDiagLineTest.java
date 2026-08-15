// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code jk history} replay must carry everything the journal persists for a failure —
 * not flatten it back to task+message while the enrichment rots unread on disk.
 */
class HistoryDiagLineTest {

    @Test
    void replay_line_round_trips_the_full_persisted_diag() {
        var d = new BuildRecord.Diag(
                "error",
                "/w/core",
                "run-tests",
                "test-failure",
                "expected 1 but was 2",
                "Foo.bar()",
                "org.opentest4j.AssertionFailedError",
                "g:core",
                "junit-jupiter",
                "cc.jumpkick.FooTest",
                "bar()",
                "AssertionFailedError: nope\n\tat cc.jumpkick.FooTest.bar(FooTest.java:9)",
                "src/test/java/cc/jumpkick/FooTest.java",
                9,
                6,
                List.of("int a = 1;", "assertEquals(1, 2);"),
                2);

        String line = JournalWriter.historyDiagLine(d);

        assertThat(Jsonl.str(line, "severity")).isEqualTo("error");
        assertThat(Jsonl.str(line, "task")).isEqualTo("run-tests");
        assertThat(Jsonl.topStr(line, "class")).isEqualTo("cc.jumpkick.FooTest");
        assertThat(Jsonl.topStr(line, "method")).isEqualTo("bar()");
        assertThat(Jsonl.str(line, "module")).isEqualTo("g:core");
        assertThat(Jsonl.str(line, "engine")).isEqualTo("junit-jupiter");
        assertThat(Jsonl.str(line, "stack")).contains("FooTest.java:9");
        assertThat(Jsonl.str(line, "file")).isEqualTo("src/test/java/cc/jumpkick/FooTest.java");
        assertThat(Jsonl.intValue(line, "line", 0)).isEqualTo(9);
        assertThat(Jsonl.intValue(line, "snippetStart", 0)).isEqualTo(6);
        assertThat(Jsonl.strArray(line, "snippet")).containsExactly("int a = 1;", "assertEquals(1, 2);");
        assertThat(Jsonl.intValue(line, "worker", 0)).isEqualTo(2);
    }

    @Test
    void empty_enrichment_stays_off_the_wire() {
        var d = new BuildRecord.Diag("warning", "", "lock", "resolve", "no versions", "", "");
        String line = JournalWriter.historyDiagLine(d);
        assertThat(Jsonl.str(line, "severity")).isEqualTo("warning");
        assertThat(line)
                .doesNotContain("\"stack\"")
                .doesNotContain("\"snippet\"")
                .doesNotContain("\"worker\"");
    }
}
