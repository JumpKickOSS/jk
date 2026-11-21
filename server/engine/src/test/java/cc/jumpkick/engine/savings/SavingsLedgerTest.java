// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.sessions.ActionRecord;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.runtime.TestsNotRerun;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SavingsLedgerTest {

    private static final long T = 1_700_000_000_000L;

    private static BuildRecord run(
            @Nullable String projectId,
            String dir,
            long finishedAt,
            BuildRecord.@Nullable CacheBenefit benefit,
            List<BuildRecord.Module> modules,
            List<BuildRecord.Task> steps,
            List<BuildRecord.Diag> diags) {
        return new BuildRecord(
                null,
                1L,
                BuildRecord.SCHEMA,
                "build",
                dir,
                "g:" + dir.substring(dir.lastIndexOf('/') + 1),
                projectId,
                finishedAt - 100,
                finishedAt,
                100,
                diags.stream().noneMatch(d -> "error".equals(d.severity())),
                false,
                0,
                "9.9-test",
                null,
                modules,
                steps,
                diags,
                "cli",
                null,
                null,
                benefit,
                false,
                null,
                0L,
                null,
                List.of());
    }

    private static BuildRecord.Task task(String name, String status) {
        return new BuildRecord.Task(name, "", status, 10, 0);
    }

    private static BuildRecord.Diag diag(String severity, String step, String code, String message) {
        return new BuildRecord.Diag(severity, "", step, code, message, null, null);
    }

    private static SessionLog.Acted acted(String kind, String outcome, String dir, int edits, @Nullable String id) {
        return new SessionLog.Acted(new ActionRecord(1, T, kind, kind, null, outcome, 5, "s.1", dir, 0, edits), id);
    }

    @Test
    void a_run_counts_its_estimate_its_skipped_steps_and_suites_its_classes_not_rerun_and_its_guard_stops() {
        BuildRecord r = run(
                "p-app",
                "/ws/app",
                T,
                new BuildRecord.CacheBenefit(90_000, 60_000, 3, 4),
                List.of(new BuildRecord.Module(
                        "g:core",
                        "/ws/app/core",
                        true,
                        0,
                        50,
                        List.of(
                                task("compile-java", "SKIPPED"),
                                task("run-tests", "SKIPPED"),
                                task("run-tests@integration", "SUCCESS")))),
                List.of(task("guard-tree", "FAIL"), task("resolve", "SKIPPED")),
                List.of(
                        diag("warning", "run-tests", TestsNotRerun.CODE, TestsNotRerun.message(6)),
                        diag("error", "guard-tree", "no-println", "System.out in Main.java:3"),
                        diag("error", "guard", "layers", "core reaches web"),
                        diag("error", "compile-java", "javac", "cannot find symbol")));

        Savings s = SavingsLedger.of(r);

        assertThat(s.runs()).isEqualTo(1);
        assertThat(s.savedMillis()).isEqualTo(60_000);
        assertThat(s.estimatedUncachedMillis()).isEqualTo(90_000);
        assertThat(s.coveredSkips()).isEqualTo(3);
        assertThat(s.estimatedSkips()).isEqualTo(4);
        assertThat(s.stepsSkipped()).isEqualTo(3);
        assertThat(s.suitesSkipped()).isEqualTo(1);
        assertThat(s.testClassesNotRerun()).isEqualTo(6);
        assertThat(s.guardViolations())
                .as("a compile error is no guard violation")
                .isEqualTo(2);
        assertThat(s.guardStops()).isEqualTo(1);
    }

    @Test
    void an_action_counts_a_question_answered_a_rename_applied_or_its_edits_and_a_failed_one_nothing() {
        assertThat(SavingsLedger.of(acted("callers", ActionRecord.OK, "/ws", 0, null)
                                .action())
                        .graphCalls())
                .isEqualTo(1);
        assertThat(SavingsLedger.of(
                                acted("rename", ActionRecord.OK, "/ws", 0, null).action())
                        .renames())
                .isEqualTo(1);
        assertThat(SavingsLedger.of(
                                acted("deps", ActionRecord.OK, "/ws", 2, null).action())
                        .edits())
                .isEqualTo(2);
        Savings failed = SavingsLedger.of(
                acted("query", ActionRecord.ERROR, "/ws", 0, null).action());
        assertThat(failed.nothing()).isTrue();
        Savings failedRename = SavingsLedger.of(
                acted("rename", ActionRecord.ERROR, "/ws", 0, null).action());
        assertThat(failedRename.renames()).isZero();
    }

    @Test
    void the_total_comes_first_then_each_project_with_the_most_saved_first() {
        List<BuildRecord> runs = List.of(
                run(
                        "p-lib",
                        "/ws/lib",
                        T + 3,
                        new BuildRecord.CacheBenefit(10, 5, 1, 1),
                        List.of(),
                        List.of(),
                        List.of()),
                run(
                        "p-app",
                        "/ws/app2",
                        T + 2,
                        new BuildRecord.CacheBenefit(100, 70, 1, 1),
                        List.of(),
                        List.of(),
                        List.of()),
                run(
                        "p-app",
                        "/ws/app",
                        T + 1,
                        new BuildRecord.CacheBenefit(100, 30, 1, 1),
                        List.of(),
                        List.of(),
                        List.of()),
                run(null, "/tmp/loose", T, null, List.of(), List.of(), List.of()));
        List<SessionLog.Acted> actions = List.of(
                acted("query", ActionRecord.OK, "/ws/app", 0, "p-app"),
                acted("rename", ActionRecord.OK, "/ws/lib", 0, null),
                acted("deps", ActionRecord.OK, "/ws/other", 1, null),
                acted("find", ActionRecord.ERROR, "/ws/app", 0, "p-app"));

        List<Savings> rows = SavingsLedger.fold(runs, actions, T - 1, T + 10);

        Savings all = rows.getFirst();
        assertThat(all.scope()).isEqualTo(Savings.ALL);
        assertThat(all.projectId()).isNull();
        assertThat(all.runs()).isEqualTo(4);
        assertThat(all.savedMillis()).isEqualTo(105);
        assertThat(all.graphCalls()).isEqualTo(1);
        assertThat(all.renames()).isEqualTo(1);
        assertThat(all.edits()).isEqualTo(1);
        assertThat(all.since()).isEqualTo(T - 1);
        assertThat(all.until()).isEqualTo(T + 10);

        assertThat(rows.subList(1, rows.size()))
                .extracting(Savings::projectId, Savings::dir)
                .containsExactly(
                        tuple("p-app", "/ws/app2"),
                        tuple("p-lib", "/ws/lib"),
                        tuple(null, "/tmp/loose"),
                        tuple(null, "/ws/other"));
        Savings app = rows.get(1);
        assertThat(app.scope()).isEqualTo(Savings.PROJECT);
        assertThat(app.coord()).as("the newest run names the project").isEqualTo("g:app2");
        assertThat(app.runs()).isEqualTo(2);
        assertThat(app.savedMillis()).isEqualTo(100);
        assertThat(app.graphCalls()).isEqualTo(1);
        Savings lib = rows.get(2);
        assertThat(lib.renames())
                .as("an action with no id joins the project its directory ran")
                .isEqualTo(1);
    }

    @Test
    void a_run_or_action_after_the_period_is_left_out() {
        List<Savings> rows = SavingsLedger.fold(
                List.of(run(
                        "p",
                        "/ws",
                        T + 100,
                        new BuildRecord.CacheBenefit(9, 9, 1, 1),
                        List.of(),
                        List.of(),
                        List.of())),
                List.of(new SessionLog.Acted(
                        new ActionRecord(1, T + 100, "query", "q", null, ActionRecord.OK, 1, "s.1", "/ws", 0, 0), "p")),
                T,
                T + 50);
        assertThat(rows).singleElement().satisfies(all -> assertThat(all.nothing())
                .isTrue());
    }

    @Test
    void a_row_maps_to_the_fields_jk_stats_and_the_dashboard_read() {
        Savings all = SavingsLedger.fold(List.of(), List.of(), 5, 9).getFirst();
        assertThat(all.toMap())
                .containsOnlyKeys(
                        "scope",
                        "since",
                        "until",
                        "runs",
                        "savedMillis",
                        "estimatedUncachedMillis",
                        "coveredSkips",
                        "estimatedSkips",
                        "stepsSkipped",
                        "suitesSkipped",
                        "testClassesNotRerun",
                        "graphCalls",
                        "renames",
                        "edits",
                        "guardViolations",
                        "guardStops")
                .containsEntry("scope", "all")
                .containsEntry("since", 5L);
    }
}
