// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.FormatWorkerStub;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code jk format} worker that dies mid-run fails the plan.
 *
 * <p>{@code BuildPlanResult.success()} is the verdict the journal, dashboard and terminal wedge
 * all read, so a SIGSEGV after a partial visit cannot report as a complete format. These run a
 * <em>real fork</em> ({@link FormatWorkerStub}) through the real {@code PluginClient} dispatch.
 * {@link FormatReconcileTest} covers the arithmetic itself.
 */
@Tag("integration")
class FormatWorkerCompletenessTest {

    /** One run of the format step: the plan (for its published counts) and its terminal result. */
    private record Run(BuildPlan plan, BuildPlanResult result, List<String> observed) {}

    /**
     * Drive {@code FormatWorker.runWorker} inside a real one-step {@link BuildPlan}, forking {@link
     * FormatWorkerStub} to play the worker. {@code total} is what the run set out to visit; the
     * status/path pairs are what the worker actually reports before exiting with {@code exit}.
     */
    private static Run run(
            int preClean,
            int total,
            boolean check,
            FormatFreshnessIndex freshness,
            int exit,
            List<String> statuses,
            List<Path> files) {
        List<String> observed = new ArrayList<>();
        List<String> command = new ArrayList<>(List.of(
                System.getProperty("java.home") + "/bin/java",
                "-cp",
                System.getProperty("java.class.path"),
                FormatWorkerStub.class.getName(),
                String.valueOf(exit)));
        for (int i = 0; i < statuses.size(); i++) {
            command.add(statuses.get(i));
            command.add(files.get(i).toString());
        }
        Task format = Task.builder("format")
                .ticks(total)
                .execute(ctx -> FormatWorker.runWorker(
                        ctx,
                        command,
                        preClean,
                        total,
                        check,
                        freshness,
                        (path, status, msg, index, tot) -> observed.add(status)))
                .build();
        BuildPlan plan = BuildPlan.builder("format")
                .stateKeys(
                        FormatWorker.CHANGED,
                        FormatWorker.CLEAN,
                        FormatWorker.ERRORS,
                        FormatWorker.TOTAL,
                        FormatWorker.WORKER_EXIT,
                        FormatWorker.PRE_CLEAN)
                .addTask(format)
                .build();
        return new Run(plan, plan.run(), observed);
    }

    private static List<Path> sources(Path dir, int n) throws Exception {
        Files.createDirectories(dir);
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Path f = dir.resolve("F" + i + ".java");
            Files.writeString(f, "class F" + i + " {}\n");
            files.add(f);
        }
        return files;
    }

    private static List<String> repeat(String status, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(status);
        return out;
    }

    /**
     * Eight files planned, three reported, then the worker dies with a SIGSEGV's 139.
     * The assertion that matters is on the plan verdict, not the exit code.
     */
    @Test
    void a_worker_that_dies_mid_run_fails_the_plan(@TempDir Path tmp) throws Exception {
        List<Path> files = sources(tmp, 8);

        Run r = run(0, 8, false, null, 139, repeat("clean", 3), files.subList(0, 3));

        assertThat(r.result().success())
                .as("a format that visited 3 of 8 files is not a successful, complete format")
                .isFalse();
        assertThat(r.result().errors()).hasSize(1);
        BuildPlanResult.Diagnostic d = r.result().errors().get(0);
        assertThat(d.step()).isEqualTo("format");
        assertThat(d.message())
                .contains("3 of 8")
                .contains("5 were never visited")
                .contains("139");
        // The partial counts are still published — honest partial numbers, on a failed run.
        assertThat(r.plan().get(FormatWorker.CHANGED)).contains(0);
        assertThat(r.plan().get(FormatWorker.CLEAN)).contains(3);
        assertThat(r.plan().get(FormatWorker.ERRORS)).contains(0);
        assertThat(r.plan().get(FormatWorker.WORKER_EXIT)).contains(139);
        assertThat(r.observed()).hasSize(3);
    }

    /**
     * The wire — and, through it, the journal row and the dashboard. {@code FormatVerb} encodes the
     * same {@code result.success()} into the terminal plan-finish, and {@code PlanBurst} turns it
     * into the {@code JobOutcome} the accumulator stamps on the journal entry.
     */
    @Test
    void the_plan_finish_event_for_a_dead_worker_carries_success_false(@TempDir Path tmp) throws Exception {
        List<Path> files = sources(tmp, 8);

        Run r = run(0, 8, false, null, 139, repeat("clean", 3), files.subList(0, 3));
        String line = ProtoEvents.planFinishFormat(
                EngineProtocol.SINGLE_PLAN_DIR,
                r.result().success(),
                r.plan().get(FormatWorker.TOTAL).orElse(8),
                r.plan().get(FormatWorker.WORKER_EXIT).orElse(-1));

        assertThat(line).contains("\"success\":false").contains("\"formatWorkerExit\":139");
    }

    /**
     * A completeness fix that also stamped the files the worker never reached would be strictly
     * worse than the bug: the shortfall would be invisible <em>and</em> permanent. So the index
     * keeps exactly what was reported, and a re-opened index still calls the rest dirty.
     */
    @Test
    void the_freshness_index_records_only_what_the_worker_reported(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        Path cache = tmp.resolve("cache");
        List<Path> files = sources(project.resolve("src"), 8);
        String configKey = "abcdef0123456789";
        FormatFreshnessIndex index = FormatFreshnessIndex.open(cache, project, configKey);

        Run r = run(0, 8, false, index, 139, repeat("clean", 3), files.subList(0, 3));

        assertThat(r.result().success()).isFalse();
        FormatFreshnessIndex reopened = FormatFreshnessIndex.open(cache, project, configKey);
        for (Path visited : files.subList(0, 3)) {
            assertThat(reopened.isClean(visited))
                    .as("%s was reported clean before the crash", visited.getFileName())
                    .isTrue();
        }
        for (Path missed : files.subList(3, 8)) {
            assertThat(reopened.isClean(missed))
                    .as("%s was never visited — the next run must send it again", missed.getFileName())
                    .isFalse();
        }
        assertThat(reopened.partition(files, List.of()).dirtyJava()).hasSize(5);
    }

    /** A run that visited every file it planned to visit still passes. */
    @Test
    void a_complete_run_succeeds(@TempDir Path tmp) throws Exception {
        List<Path> files = sources(tmp, 8);

        Run r = run(0, 8, false, null, 0, repeat("clean", 8), files);

        assertThat(r.result().success()).isTrue();
        assertThat(r.result().errors()).isEmpty();
        assertThat(r.plan().get(FormatWorker.CLEAN)).contains(8);
        assertThat(r.plan().get(FormatWorker.WORKER_EXIT)).contains(0);
    }

    /**
     * {@code 1} is the worker's legitimate {@code --check} drift code, not a death. Gating on "the
     * exit was non-zero" would turn every unformatted tree into a crash report, which is why the
     * gate is arithmetic.
     */
    @Test
    void check_mode_drift_is_a_verdict_not_a_crash(@TempDir Path tmp) throws Exception {
        List<Path> files = sources(tmp, 8);
        List<String> statuses = new ArrayList<>(repeat("changed", 2));
        statuses.addAll(repeat("clean", 6));

        Run r = run(0, 8, true, null, 1, statuses, files);

        assertThat(r.result().success())
                .as("drift is what --check is for; the plan ran to completion")
                .isTrue();
        assertThat(r.plan().get(FormatWorker.CHANGED)).contains(2);
        assertThat(r.plan().get(FormatWorker.CLEAN)).contains(6);
        assertThat(r.plan().get(FormatWorker.WORKER_EXIT)).contains(1);
    }

    /** {@code skipped} (unnamed class) is a visited file and must not fail the run. */
    @Test
    void skipped_files_are_visited_files_and_fail_nothing(@TempDir Path tmp) throws Exception {
        List<Path> files = sources(tmp, 8);
        List<String> statuses = new ArrayList<>(repeat("skipped", 3));
        statuses.addAll(repeat("clean", 5));

        Run r = run(0, 8, true, null, 0, statuses, files);

        assertThat(r.result().success()).isTrue();
        assertThat(r.result().errors()).isEmpty();
        assertThat(r.plan().get(FormatWorker.CHANGED)).contains(0);
        assertThat(r.plan().get(FormatWorker.CLEAN)).contains(8);
        assertThat(r.plan().get(FormatWorker.ERRORS)).contains(0);
    }

    /**
     * The case the count alone cannot see: the worker reported on everything and then died on the
     * way out. Without this arm the wedge would print green while the process returned 139 — the
     * same two-readers-disagreeing defect, one file later.
     */
    @Test
    void a_crash_after_the_last_file_still_fails(@TempDir Path tmp) throws Exception {
        List<Path> files = sources(tmp, 8);

        Run r = run(0, 8, false, null, 139, repeat("clean", 8), files);

        assertThat(r.result().success()).isFalse();
        assertThat(r.result().errors().get(0).message()).contains("exited 139").contains("exit law is 0 or 1");
    }

    /** Files the freshness index settled before the fork are part of the total, so they count. */
    @Test
    void files_settled_before_the_fork_count_toward_the_total(@TempDir Path tmp) throws Exception {
        List<Path> files = sources(tmp, 3);

        Run r = run(5, 8, false, null, 0, repeat("clean", 3), files);

        assertThat(r.result().success()).isTrue();
        assertThat(r.plan().get(FormatWorker.CLEAN)).contains(8);
    }
}
