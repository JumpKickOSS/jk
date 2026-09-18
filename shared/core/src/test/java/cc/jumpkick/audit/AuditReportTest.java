// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.audit;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.audit.AuditReport.Finding;
import cc.jumpkick.audit.AuditReport.Severity;
import cc.jumpkick.model.BuildBlock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ignore filter: which findings an {@code [audit] ignore} entry keeps out of the gate, and for how long. */
class AuditReportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);

    private static Finding finding(String id, Severity severity) {
        return new Finding("g:a", "1.0", id, "summary of " + id, severity, null);
    }

    @Test
    void an_entry_naming_the_advisory_ignores_it_and_the_first_match_wins() {
        List<BuildBlock.AuditIgnore> ignores = List.of(
                new BuildBlock.AuditIgnore("GHSA-1", "first", null),
                new BuildBlock.AuditIgnore("GHSA-1", "second", null));
        Finding judged = finding("GHSA-1", Severity.HIGH).under(ignores, TODAY);
        assertThat(judged.ignored()).isTrue();
        assertThat(judged.ignore()).isEqualTo(new AuditReport.Ignore("first", null, false));
        assertThat(finding("GHSA-2", Severity.HIGH).under(ignores, TODAY).ignore())
                .isNull();
    }

    /** OSV spells the suffix in lower case; a hand-typed entry may not. */
    @Test
    void ids_match_case_insensitively() {
        Finding judged = finding("GHSA-abcd-efgh", Severity.HIGH)
                .under(List.of(new BuildBlock.AuditIgnore("ghsa-ABCD-efgh", "typed by hand", null)), TODAY);
        assertThat(judged.ignored()).isTrue();
    }

    @Test
    void a_dated_entry_ignores_through_its_last_day_and_expires_after_it() {
        List<BuildBlock.AuditIgnore> ignores =
                List.of(new BuildBlock.AuditIgnore("GHSA-1", "waiting on upstream", TODAY));
        Finding onTheDay = finding("GHSA-1", Severity.HIGH).under(ignores, TODAY);
        assertThat(onTheDay.ignored()).isTrue();
        assertThat(onTheDay.ignoreExpired()).isFalse();

        Finding dayAfter = finding("GHSA-1", Severity.HIGH).under(ignores, TODAY.plusDays(1));
        assertThat(dayAfter.ignored()).isFalse();
        assertThat(dayAfter.ignoreExpired()).isTrue();
        assertThat(dayAfter.ignore()).isEqualTo(new AuditReport.Ignore("waiting on upstream", TODAY, true));
    }

    @Test
    void blocking_is_the_threshold_filter_minus_the_ignored_and_an_expired_entry_blocks_again() {
        Finding high = finding("GHSA-high", Severity.HIGH);
        Finding ignoredHigh = finding("GHSA-quiet", Severity.HIGH).withIgnore(new AuditReport.Ignore("r", null, false));
        Finding expiredCritical =
                finding("GHSA-late", Severity.CRITICAL).withIgnore(new AuditReport.Ignore("r", TODAY, true));
        Finding low = finding("GHSA-low", Severity.LOW);
        AuditReport report = new AuditReport(List.of(high, ignoredHigh, expiredCritical, low));

        assertThat(report.filterAtLeast(Severity.HIGH))
                .as("the threshold filter alone still lists the ignored one")
                .containsExactly(high, ignoredHigh, expiredCritical);
        assertThat(report.blocking(Severity.HIGH)).containsExactly(high, expiredCritical);
        assertThat(report.blocking(Severity.LOW)).containsExactly(high, expiredCritical, low);
        assertThat(report.ignored()).containsExactly(ignoredHigh);
    }

    /** An unclassifiable advisory fails closed unless an entry names it — then it is a decision, not a gap. */
    @Test
    void an_unknown_severity_gates_unless_ignored() {
        Finding unknown = finding("CVE-9", Severity.UNKNOWN);
        assertThat(new AuditReport(List.of(unknown)).blocking(Severity.CRITICAL))
                .containsExactly(unknown);
        Finding decided = unknown.withIgnore(new AuditReport.Ignore("vector-only advisory, reviewed", null, false));
        assertThat(new AuditReport(List.of(decided)).blocking(Severity.CRITICAL))
                .isEmpty();
    }

    @Test
    void the_markdown_says_ignored_with_the_reason_expired_when_lapsed_and_names_the_fix() {
        Finding fixed = new Finding("g:a", "1.0", "GHSA-fix", "s", Severity.HIGH, "1.1");
        Finding quiet = finding("GHSA-quiet", Severity.HIGH)
                .withIgnore(new AuditReport.Ignore("test only", LocalDate.of(2026, 12, 31), false));
        Finding lapsed =
                finding("GHSA-late", Severity.LOW).withIgnore(new AuditReport.Ignore("was waiting", TODAY, true));
        String md = new AuditReport(List.of(fixed, quiet, lapsed)).renderMarkdown();
        assertThat(md)
                .contains("3 findings by severity: HIGH=2, LOW=1 (1 ignored)")
                .contains("GHSA-fix) — s — fixed in 1.1")
                .contains("GHSA-quiet) — summary of GHSA-quiet — ignored (test only, until 2026-12-31)")
                .contains("GHSA-late) — summary of GHSA-late — ignore expired (was waiting, until 2026-09-11)");
    }
}
