// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SecretRedactor;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A failed test's snapshot, from the runner's {@code throwable.snapshot} to the lines its module's
 * results hold: masked as every other line the fork sends, held to the report's budget, and
 * dropped with its class when a retry forgets the class.
 */
class FailureSnapshotReportTest {

    private static final String SECRET = "hunter2-db-password";

    private static String failed(String snapshot) {
        return "{\"event\":\"finished\",\"uniqueId\":\"[class:com.acme.OrderTest]/[method:ships()]\","
                + "\"testClass\":\"com.acme.OrderTest\",\"testMethod\":\"ships()\",\"type\":\"TEST\","
                + "\"status\":\"FAILED\",\"throwable\":{\"class\":\"java.lang.IllegalStateException\","
                + "\"message\":\"only 2 lines\",\"stack\":\"java.lang.IllegalStateException: only 2 lines\","
                + "\"snapshot\":" + snapshot + "}}";
    }

    private static MarkdownTestReport.Held recorded(String snapshot, SecretRedactor secrets) {
        MarkdownTestReport md = new MarkdownTestReport();
        var agg = new ResultAggregator(TestProgressListener.noop(), 0, null, md, "g:app", secrets);
        agg.accept(failed(snapshot));
        return md.forget(List.of());
    }

    private static List<String> lines(MarkdownTestReport.Held held) {
        return Objects.requireNonNull(held.snapshots().get("com.acme.OrderTest#ships"), "no snapshot")
                .lines();
    }

    @Test
    void a_failure_s_snapshot_is_one_line_per_frame_under_the_test_s_key() {
        var held = recorded(
                "[{\"at\":\"Order.ship(Order.java:50)\",\"vars\":[\"qty=3\",\"available=2\"]},"
                        + "{\"at\":\"OrderTest.ships(OrderTest.java:12)\",\"vars\":[]}]",
                SecretRedactor.none());
        assertThat(held.snapshots()).containsKey("com.acme.OrderTest#ships");
        assertThat(lines(held))
                .containsExactly("Order.ship(Order.java:50) qty=3, available=2", "OrderTest.ships(OrderTest.java:12)");
    }

    @Test
    void a_secret_in_a_local_is_masked_whole_and_where_the_runner_cut_it() {
        var held = recorded(
                "[{\"at\":\"Db.open(Db.java:9)\",\"vars\":[\"password=\\\"" + SECRET + "\\\"\","
                        + "\"url=\\\"jdbc:x?pw=" + SECRET.substring(0, 9) + FailureClip.VALUE_TRUNCATION_MARKER
                        + "40 chars)\"]}]",
                SecretRedactor.of(Set.of(SECRET)));
        String line = lines(held).getFirst();
        assertThat(line).doesNotContain(SECRET).doesNotContain(SECRET.substring(0, 9));
        assertThat(line).contains("password=\"" + SecretRedactor.MASK + "\"");
    }

    @Test
    void the_report_keeps_four_frames_and_cuts_a_long_line() {
        StringBuilder frames = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) frames.append(',');
            frames.append("{\"at\":\"A.f")
                    .append(i)
                    .append("(A.java:1)\",\"vars\":[\"x=")
                    .append("y".repeat(600))
                    .append("\"]}");
        }
        var lines = lines(recorded(frames.append(']').toString(), SecretRedactor.none()));
        assertThat(lines).hasSize(FailureSnapshotLines.MAX_FRAMES);
        assertThat(lines.getFirst())
                .hasSizeLessThan(FailureSnapshotLines.MAX_LINE_CHARS + 40)
                .contains(FailureClip.VALUE_TRUNCATION_MARKER);
    }

    @Test
    void a_failure_without_one_records_nothing_and_a_retry_forgets_it_with_its_class() {
        assertThat(recorded("[]", SecretRedactor.none()).snapshots()).isEmpty();
        MarkdownTestReport md = new MarkdownTestReport();
        md.recordSnapshot("com.acme.OrderTest", List.of("ships()"), List.of("A.a(A.java:1) x=1"));
        md.recordSnapshot("com.acme.OtherTest", List.of("runs()"), List.of("B.b(B.java:2) y=2"));
        var held = md.forget(List.of("com.acme.OrderTest"));
        assertThat(held.snapshots()).containsOnlyKeys("com.acme.OrderTest#ships");
        md.restore(held);
        assertThat(md.forget(List.of()).snapshots())
                .containsOnlyKeys("com.acme.OrderTest#ships", "com.acme.OtherTest#runs");
    }
}
