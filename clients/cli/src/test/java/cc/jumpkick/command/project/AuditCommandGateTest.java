// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.audit.AuditReport.Finding;
import cc.jumpkick.audit.AuditReport.Severity;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.command.Exit;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code jk audit}'s exit decision and the shape of its {@code --output json} lines. */
class AuditCommandGateTest {

    private static Finding finding(String id, Severity severity) {
        return new Finding("com.example:lib", "1.0", id, "summary", severity, null);
    }

    @Test
    void a_finding_at_or_above_the_threshold_fails_and_one_below_it_passes() {
        assertThat(AuditCommand.exitFor(new AuditReport(List.of(finding("GHSA-h", Severity.HIGH))), Severity.HIGH))
                .isEqualTo(Exit.FAILURE);
        assertThat(AuditCommand.exitFor(new AuditReport(List.of(finding("GHSA-m", Severity.MEDIUM))), Severity.HIGH))
                .isEqualTo(Exit.SUCCESS);
        assertThat(AuditCommand.exitFor(new AuditReport(List.of()), Severity.LOW))
                .isEqualTo(Exit.SUCCESS);
    }

    @Test
    void an_ignored_finding_does_not_count_and_an_expired_ignore_counts_again() {
        Finding quiet =
                finding("GHSA-h", Severity.CRITICAL).withIgnore(new AuditReport.Ignore("accepted", null, false));
        assertThat(AuditCommand.exitFor(new AuditReport(List.of(quiet)), Severity.LOW))
                .isEqualTo(Exit.SUCCESS);

        Finding lapsed = quiet.withIgnore(new AuditReport.Ignore("accepted", LocalDate.of(2026, 1, 1), true));
        assertThat(AuditCommand.exitFor(new AuditReport(List.of(lapsed)), Severity.LOW))
                .isEqualTo(Exit.FAILURE);
    }

    @Test
    void the_verdict_counts_what_blocked_and_what_an_ignore_kept_out() {
        Finding quiet = finding("GHSA-q", Severity.HIGH).withIgnore(new AuditReport.Ignore("accepted", null, false));
        AuditReport report = new AuditReport(List.of(finding("GHSA-h", Severity.HIGH), quiet));
        assertThat(AuditCommand.verdict(report, Severity.HIGH))
                .isEqualTo("1 finding at or above HIGH (1 ignored) — failing.");
        assertThat(AuditCommand.verdict(
                        new AuditReport(List.of(finding("a", Severity.HIGH), finding("b", Severity.HIGH))),
                        Severity.LOW))
                .isEqualTo("2 findings at or above LOW — failing.");
    }

    @Test
    void a_json_line_is_the_machine_envelope_plus_one_finding() {
        String line = AuditCommand.findingJson(
                1_721_664_000_123L, new Finding("com.example:lib", "1.0", "GHSA-x", "bad news", Severity.HIGH, "1.1"));
        assertThat(line)
                .isEqualTo("{\"schema\":1,\"ts\":1721664000123,\"type\":\"audit-finding\",\"id\":\"GHSA-x\","
                        + "\"package\":\"com.example:lib\",\"version\":\"1.0\",\"severity\":\"HIGH\","
                        + "\"summary\":\"bad news\",\"fixedIn\":\"1.1\",\"ignored\":false}");
    }

    @Test
    void fixed_in_rides_only_when_osv_named_one() {
        String line = AuditCommand.findingJson(0, finding("GHSA-x", Severity.LOW));
        assertThat(line).doesNotContain("fixedIn");
        assertThat(Jsonl.bool(line, "ignored", true)).isFalse();
    }

    @Test
    void an_ignored_finding_carries_ignored_true_the_reason_and_the_date() {
        String line = AuditCommand.findingJson(
                0,
                finding("GHSA-x", Severity.HIGH)
                        .withIgnore(new AuditReport.Ignore("test-only dependency", LocalDate.of(2026, 12, 31), false)));
        assertThat(Jsonl.bool(line, "ignored", false)).isTrue();
        assertThat(Jsonl.str(line, "reason")).isEqualTo("test-only dependency");
        assertThat(Jsonl.str(line, "until")).isEqualTo("2026-12-31");
        assertThat(line).doesNotContain("ignoreExpired");
    }

    @Test
    void an_expired_ignore_is_not_ignored_and_says_so() {
        String line = AuditCommand.findingJson(
                0,
                finding("GHSA-x", Severity.HIGH)
                        .withIgnore(new AuditReport.Ignore("was waiting", LocalDate.of(2026, 1, 1), true)));
        assertThat(Jsonl.bool(line, "ignored", true)).isFalse();
        assertThat(Jsonl.bool(line, "ignoreExpired", false)).isTrue();
        assertThat(Jsonl.str(line, "reason")).isEqualTo("was waiting");
    }
}
