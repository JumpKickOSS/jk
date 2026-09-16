// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The number printed beside {@code cancelled}. {@code jk history} has always been able to say a run
 * was cancelled; what it could not say is anything useful in {@code exitCode}, which was {@code 1}
 * for every cancel and therefore indistinguishable from an ordinary failed build.
 *
 * <p>One derivation, so the table is total: a row labelled cancelled carries {@link
 * Exit#INTERRUPTED}, every other row keeps the code its own verdict earned, and the abandoned row —
 * an engine that died, which is not something a user did — stays {@link Exit#SOFTWARE}.
 */
class CancelledExitCodeTest {

    private static BuildAccumulator acc() {
        return new BuildAccumulator("build", "/w", "g:w", "cli");
    }

    private static BuildRecord record(BuildAccumulator a, boolean cancelHint) {
        return a.toRecord(2_000L, cancelHint, 100L, "9.9.9", null);
    }

    @Test
    void a_cancelled_row_carries_the_interrupt_code_the_shell_already_means() {
        BuildAccumulator a = acc();
        a.markUserCancelled(true);

        BuildRecord r = record(a, true);

        assertThat(r.cancelled()).isTrue();
        assertThat(r.exitCode()).isEqualTo(Exit.INTERRUPTED);
    }

    /**
     * A cancel that arrives after a module already failed is still a cancel — the row says so, and
     * the code beside it must say the same thing the flag does rather than the failure's own 1.
     */
    @Test
    void an_explicit_cancel_after_a_module_failure_still_reads_as_an_interrupt() {
        BuildAccumulator a = acc();
        a.addModule(new ModuleOutcome("g:a", Path.of("/w/a"), false, Exit.FAILURE, 10, true));
        a.markUserCancelled(true);

        BuildRecord r = record(a, true);

        assertThat(r.cancelled()).isTrue();
        assertThat(r.exitCode()).isEqualTo(Exit.INTERRUPTED);
    }

    @Test
    void an_ordinary_failure_keeps_its_own_exit_code_and_is_not_cancelled() {
        BuildAccumulator a = acc();
        a.stamp(JobOutcome.failed(Exit.DATA_ERR));

        BuildRecord r = record(a, false);

        assertThat(r.cancelled()).isFalse();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(Exit.DATA_ERR);
    }

    /**
     * The end-of-request EOF trips the cancel hint on a run that already succeeded. That row is not
     * cancelled, so it must not pick up the interrupt code on the way past.
     */
    @Test
    void a_successful_run_is_never_given_the_interrupt_code_by_the_end_of_request_eof() {
        BuildAccumulator a = acc();
        a.stamp(JobOutcome.ok());

        BuildRecord r = record(a, true);

        assertThat(r.cancelled()).isFalse();
        assertThat(r.success()).isTrue();
        assertThat(r.exitCode()).isEqualTo(Exit.SUCCESS);
    }

    /**
     * Fail-fast: a module fails, the body rules {@code failed}, and a sibling still in flight ends
     * its plan cancelled when the request is torn down. That plan's cancel is the failure's
     * collateral, and the row keeps the failure's own verdict and code.
     */
    @Test
    void a_sibling_plan_stopped_by_fail_fast_after_the_failure_does_not_make_the_run_cancelled() {
        BuildAccumulator a = acc();
        a.addModule(new ModuleOutcome("g:a", Path.of("/w/a"), false, Exit.FAILURE, 10, true));
        a.stamp(JobOutcome.failed(Exit.FAILURE));
        a.addBuildPlan("/w/b", cancelledPlan("b"));
        a.addModule(new ModuleOutcome("g:b", Path.of("/w/b"), false, Exit.TESTS_FAILED, 20, true, true));

        BuildRecord r = record(a, true);

        assertThat(r.cancelled()).isFalse();
        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isEqualTo(Exit.FAILURE);
    }

    /** The same sibling plan landing before the body has stamped: the recorded failure still rules. */
    @Test
    void a_sibling_plan_stopped_by_fail_fast_before_the_stamp_does_not_make_the_run_cancelled() {
        BuildAccumulator a = acc();
        a.addModule(new ModuleOutcome("g:a", Path.of("/w/a"), false, Exit.FAILURE, 10, true));
        a.addBuildPlan("/w/b", cancelledPlan("b"));
        a.stamp(JobOutcome.failed(Exit.FAILURE));

        BuildRecord r = record(a, true);

        assertThat(r.cancelled()).isFalse();
        assertThat(r.exitCode()).isEqualTo(Exit.FAILURE);
    }

    /** A plan cancelled with no failure anywhere is what a Ctrl-C looks like from inside a plan. */
    @Test
    void a_plan_cancelled_with_no_failure_before_it_is_the_users_cancel() {
        BuildAccumulator a = acc();
        a.addBuildPlan("/w/a", cancelledPlan("a"));

        BuildRecord r = record(a, true);

        assertThat(r.cancelled()).isTrue();
        assertThat(r.exitCode()).isEqualTo(Exit.INTERRUPTED);
    }

    private static BuildPlanResult cancelledPlan(String name) {
        return new BuildPlanResult(
                name,
                false,
                Duration.ofMillis(20),
                List.of(new BuildPlanResult.StepReport(
                        "run-tests", TaskStatus.CANCELLED, Duration.ofMillis(20), List.of())),
                List.of(),
                List.of(new BuildPlanResult.Diagnostic("run-tests", "cancelled", "test run cancelled")),
                true,
                true);
    }

    /**
     *'s row, re-asserted from the other side: now that a genuine cancel owns {@code 130},
     * an abandoned run must still be the one thing that is neither cancelled nor an interrupt.
     */
    @Test
    void an_abandoned_row_is_still_software_and_still_not_cancelled() {
        BuildRecord abandoned = record(acc(), false).abandoned(3_000L, "9.9.9");

        assertThat(abandoned.cancelled()).isFalse();
        assertThat(abandoned.exitCode()).isEqualTo(Exit.SOFTWARE);
        assertThat(abandoned.exitCode()).isNotEqualTo(Exit.INTERRUPTED);
    }
}
