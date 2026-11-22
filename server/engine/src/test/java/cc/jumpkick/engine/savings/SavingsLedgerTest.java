// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.sessions.ActionRecord;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.prices.PriceTable;
import cc.jumpkick.runtime.TestsNotRerun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavingsLedgerTest {

    private static final long T = 1_700_000_000_000L;

    /** Two agents' rates per graph call, in the shape the bench writes. */
    static final SavingsRates RATES = SavingsRates.parse("""
            {"measured": "2026-11-04",
             "agents": {
               "claude-code": {"model": "claude-sonnet-5-5", "cacheWrite1h": true,
                 "question": {"tasks": 8, "graphCalls": 10, "input": 10, "output": 1000, "cacheRead": 200000,
                              "cacheWrite": 20000},
                 "rename": {"tasks": 7, "graphCalls": 7, "input": 5, "output": 500, "cacheRead": 50000,
                            "cacheWrite": 5000}},
               "codex": {"model": "gpt-6.1-terra", "cacheWrite1h": false,
                 "question": {"tasks": 8, "graphCalls": 9, "input": 20000, "output": 600, "cacheRead": 200000,
                              "cacheWrite": 0}}}}
            """);

    /** Prices per million tokens: input, cache read, 5-minute and 1-hour cache write, output. */
    static final PriceTable PRICES = prices("""
            published = 2026-11-17T00:00:00Z
            [models."claude-sonnet-5-5"]
            input = 2.0
            cached_input = 0.1
            cache_write = 2.5
            cache_write_1h = 4.0
            output = 10.0
            retrieved = "2026-11-17"
            [models."claude-opus-5-5"]
            input = 4.0
            cached_input = 0.2
            cache_write = 5.0
            cache_write_1h = 8.0
            output = 20.0
            retrieved = "2026-11-16"
            [models."gpt-6.1-terra"]
            input = 1.0
            cached_input = 0.05
            output = 5.0
            retrieved = "2026-11-15"
            """);

    private static PriceTable prices(String toml) {
        try {
            return PriceTable.parse(toml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** No agent's files to read: every action is priced at the model the bench ran its agent on. */
    private static AgentModels benchModels() {
        return new AgentModels(AgentUsage.NONE);
    }

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
        return acted(kind, outcome, dir, edits, id, null);
    }

    private static SessionLog.Acted acted(
            String kind, String outcome, String dir, int edits, @Nullable String id, @Nullable String client) {
        return new SessionLog.Acted(
                new ActionRecord(1, T, kind, kind, null, outcome, 5, "s.1", dir, 0, edits), id, client);
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
    void an_action_counts_a_question_answered_a_refactoring_applied_or_its_edits_and_a_failed_one_nothing() {
        assertThat(SavingsLedger.of(
                                acted("usages", ActionRecord.OK, "/ws", 0, null).action(), TokensSaved.NONE)
                        .graphCalls())
                .isEqualTo(1);
        assertThat(SavingsLedger.of(
                                acted("rename", ActionRecord.OK, "/ws", 0, null).action(), TokensSaved.NONE)
                        .refactorings())
                .isEqualTo(1);
        assertThat(SavingsLedger.of(
                                acted("deps", ActionRecord.OK, "/ws", 2, null).action(), TokensSaved.NONE)
                        .edits())
                .isEqualTo(2);
        Savings failed = SavingsLedger.of(
                acted("query", ActionRecord.ERROR, "/ws", 0, null).action(), TokensSaved.NONE);
        assertThat(failed.nothing()).isTrue();
        Savings failedRename = SavingsLedger.of(
                acted("rename", ActionRecord.ERROR, "/ws", 0, null).action(), TokensSaved.NONE);
        assertThat(failedRename.refactorings()).isZero();
    }

    @Test
    void a_guard_rule_a_file_breaks_counts_once_however_many_runs_it_stopped() {
        BuildRecord.Diag mainPrintln = diag(
                "error", "guard", "no-println", "src/Main.java:3: System.out\n  Why:      logs go through the logger");
        BuildRecord.Diag mainPrintlnLater = diag("error", "guard", "no-println", "src/Main.java:9: System.out");
        BuildRecord.Diag otherFile = diag("error", "guard", "no-println", "src/Cli.java:3: System.out");
        BuildRecord.Diag otherRule = diag("error", "guard", "no-wildcards", "src/Main.java:1: import java.util.*");
        BuildRecord.Diag noFile = diag("error", "guard-tree", "layers", "GUARD layers  blind");
        List<BuildRecord> runs = List.of(
                run("p-app", "/ws/app", T + 3, null, List.of(), List.of(), List.of(mainPrintlnLater, noFile)),
                run("p-app", "/ws/app", T + 2, null, List.of(), List.of(), List.of(mainPrintln, otherFile, noFile)),
                run("p-app", "/ws/app", T + 1, null, List.of(), List.of(), List.of(mainPrintln, otherRule)),
                run("p-lib", "/ws/lib", T, null, List.of(), List.of(), List.of(mainPrintln)));

        List<Savings> rows =
                SavingsLedger.fold(runs, List.of(), RATES, benchModels(), ProjectLines.NONE, T - 1, T + 10);

        Savings all = rows.getFirst();
        assertThat(all.guardViolations())
                .as(
                        "no-println in app's Main and Cli, no-wildcards in app's Main, layers in app, no-println in lib's Main")
                .isEqualTo(5);
        assertThat(all.guardStops()).as("every run it stopped").isEqualTo(4);
        assertThat(all.toMap()).containsEntry("guardViolations", 5L);
        assertThat(rows.get(1).guardViolations()).isEqualTo(4);
        assertThat(rows.get(2).guardViolations()).isEqualTo(1);
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

        List<Savings> rows = SavingsLedger.fold(runs, actions, RATES, benchModels(), ProjectLines.NONE, T - 1, T + 10);

        Savings all = rows.getFirst();
        assertThat(all.scope()).isEqualTo(Savings.ALL);
        assertThat(all.projectId()).isNull();
        assertThat(all.runs()).isEqualTo(4);
        assertThat(all.savedMillis()).isEqualTo(105);
        assertThat(all.graphCalls()).isEqualTo(1);
        assertThat(all.refactorings()).isEqualTo(1);
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
        assertThat(lib.refactorings())
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
                        new ActionRecord(1, T + 100, "query", "q", null, ActionRecord.OK, 1, "s.1", "/ws", 0, 0),
                        "p",
                        null)),
                RATES,
                benchModels(),
                ProjectLines.NONE,
                T,
                T + 50);
        assertThat(rows).singleElement().satisfies(all -> assertThat(all.nothing())
                .isTrue());
    }

    @Test
    void a_row_maps_to_the_fields_jk_stats_and_the_dashboard_read() {
        Savings all = SavingsLedger.fold(List.of(), List.of(), RATES, benchModels(), ProjectLines.NONE, 5, 9)
                .getFirst();
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
                        "refactorings",
                        "edits",
                        "guardViolations",
                        "guardStops",
                        "tokensSaved",
                        "tokensSavedByModel",
                        "usdSavedMicros",
                        "tokensUnpriced",
                        "callsNotMeasured",
                        "ratesMeasured",
                        "pricesRetrieved")
                .containsEntry("scope", "all")
                .containsEntry("since", 5L);
    }

    @Test
    void a_question_and_a_refactoring_save_their_agents_tokens_per_graph_call_kept_by_model() {
        TokensSaved.Counts question = new TokensSaved.Counts(10, 1000, 200_000, 0, 20_000);
        TokensSaved.Counts rename = new TokensSaved.Counts(5, 500, 50_000, 0, 5_000);
        TokensSaved.Counts codexQuestion = new TokensSaved.Counts(20_000, 600, 200_000, 0, 0);
        assertThat(Objects.requireNonNull(RATES.agents().get("claude-code"))
                        .question()
                        .all())
                .as("Claude Code writes its cache for an hour")
                .isEqualTo(question);
        List<SessionLog.Acted> actions = List.of(
                acted("usages", ActionRecord.OK, "/ws/app", 0, "p-app", "claude-code"),
                acted("rename", ActionRecord.OK, "/ws/app", 0, "p-app", "claude-code"),
                acted("find", ActionRecord.OK, "/ws/lib", 0, "p-lib", "codex-mcp-client"),
                acted("query", ActionRecord.ERROR, "/ws/lib", 0, "p-lib", "codex-mcp-client"),
                acted("deps", ActionRecord.OK, "/ws/lib", 1, "p-lib", "codex-mcp-client"));

        List<Savings> rows =
                SavingsLedger.fold(List.of(), actions, RATES, benchModels(), ProjectLines.NONE, T - 1, T + 10);

        TokensSaved claude = TokensSaved.of("claude-sonnet-5-5", question.plus(rename));
        TokensSaved codex = TokensSaved.of("gpt-6.1-terra", codexQuestion);
        assertThat(rows.getFirst().tokens()).isEqualTo(claude.plus(codex));
        assertThat(rows.subList(1, rows.size()))
                .extracting(Savings::projectId, Savings::tokens)
                .as("a failed call and a dependency edit save no tokens")
                .containsExactlyInAnyOrder(tuple("p-app", claude), tuple("p-lib", codex));
        TokensSaved.Priced priced = rows.getFirst().tokens().priced(PRICES);
        assertThat(priced.usdMicros())
                .as("15 x $2 + 1500 x $10 + 250000 x $0.1 + 25000 x $4 (1-hour write), "
                        + "then 20000 x $1 + 600 x $5 + 200000 x $0.05, per million")
                .isEqualTo(30 + 15_000 + 25_000 + 100_000 + 20_000 + 3_000 + 10_000);
        assertThat(priced.unpriced()).isZero();
        assertThat(priced.retrieved()).as("the newest price behind the dollars").hasToString("2026-11-17");
    }

    @Test
    void the_model_the_agent_was_on_prices_its_tokens_and_a_model_without_a_price_is_left_out_and_said() {
        ActionRecord usages = acted("usages", ActionRecord.OK, "/ws/app", 0, "p-app", "claude-code")
                .action();
        TokensSaved onOpus = Objects.requireNonNull(RATES.saved(usages, "claude-code", "claude-opus-5-5", 0));
        assertThat(onOpus.byModel()).containsOnlyKeys("claude-opus-5-5");
        assertThat(onOpus.priced(PRICES).usdMicros())
                .as("the bench's tokens at the model jk read the agent was on: Opus, not Sonnet")
                .isEqualTo(40 + 20_000 + 40_000 + 160_000);
        assertThat(onOpus.priced(PRICES).retrieved()).hasToString("2026-11-16");
        TokensSaved unknown = Objects.requireNonNull(RATES.saved(usages, "claude-code", "claude-next-7", 0));
        assertThat(unknown.priced(PRICES)).isEqualTo(new TokensSaved.Priced(0, unknown.total(), null));
        assertThat(unknown.text(PRICES, 0))
                .isEqualTo("~221k, ~$0.000 (estimate, API list prices; 221k on models with no price)");
        assertThat(onOpus.plus(unknown).text(PRICES, 0))
                .isEqualTo("~442k, ~$0.220 (estimate, API list prices of 2026-11-16; 221k on models with no price)");
    }

    @Test
    void a_call_the_bench_has_no_rate_for_is_credited_nothing_and_counted_as_not_measured() {
        ActionRecord rename = acted("rename", ActionRecord.OK, "/ws", 0, null).action();
        assertThat(RATES.agentOf("Codex-MCP-Client")).isEqualTo("codex");
        assertThat(RATES.agentOf("Claude Code")).isEqualTo("claude-code");
        assertThat(SavingsRates.shipped().agentOf("antigravity-cli")).isEqualTo("agy");
        assertThat(RATES.agentOf("Some-New-Agent"))
                .as("an agent the bench never ran is not credited at another agent's rates")
                .isNull();
        assertThat(RATES.agentOf(null)).isNull();
        assertThat(RATES.saved(rename, "a-new-agent", null, 0)).isNull();
        assertThat(RATES.saved(rename, "codex", null, 0))
                .as("the bench has no codex rename")
                .isNull();
        assertThat(RATES.saved(
                        acted("rename", ActionRecord.ERROR, "/ws", 0, null).action(), "a-new-agent", null, 0))
                .as("a failed call saves nothing, measured or not")
                .isEqualTo(TokensSaved.NONE);
        assertThat(RATES.saved(acted("deps", ActionRecord.OK, "/ws", 1, null).action(), "a-new-agent", null, 0))
                .as("the bench measures no tokens for a dependency edit")
                .isEqualTo(TokensSaved.NONE);

        List<SessionLog.Acted> actions = List.of(
                acted("usages", ActionRecord.OK, "/ws/app", 0, "p-app", "claude-code"),
                acted("usages", ActionRecord.OK, "/ws/app", 0, "p-app", "some-new-agent"),
                acted("query", ActionRecord.OK, "/ws/lib", 0, "p-lib", "some-new-agent"),
                acted("rename", ActionRecord.OK, "/ws/lib", 0, "p-lib", "codex"),
                acted("query", ActionRecord.ERROR, "/ws/lib", 0, "p-lib", "some-new-agent"));
        List<Savings> rows =
                SavingsLedger.fold(List.of(), actions, RATES, benchModels(), ProjectLines.NONE, T - 1, T + 10);

        Savings all = rows.getFirst();
        TokensSaved.Counts question = Objects.requireNonNull(RATES.agents().get("claude-code"))
                .question()
                .all();
        assertThat(all.tokens())
                .as("only the measured agent's call")
                .isEqualTo(TokensSaved.of("claude-sonnet-5-5", question));
        assertThat(all.graphCalls()).as("every answer still counts as answered").isEqualTo(3);
        assertThat(all.callsNotMeasured()).isEqualTo(3);
        assertThat(rows.subList(1, rows.size()))
                .extracting(Savings::projectId, Savings::callsNotMeasured)
                .containsExactlyInAnyOrder(tuple("p-app", 1L), tuple("p-lib", 2L));
        assertThat(all.toMap()).containsEntry("callsNotMeasured", 3L);
        assertThat(all.tokens().text(PRICES, all.callsNotMeasured()))
                .isEqualTo("~221k, ~$0.110 (estimate, API list prices of 2026-11-17; "
                        + "3 calls not measured by the bench, not counted)");
        assertThat(TokensSaved.NONE.text(PRICES, 1))
                .isEqualTo("~0, ~$0.000 (estimate, API list prices; 1 call not measured by the bench, not counted)");
    }

    @Test
    void a_call_is_credited_the_rate_measured_in_the_repository_nearest_its_projects_size(@TempDir Path ws)
            throws IOException {
        SavingsRates rates = SavingsRates.parse("""
                {"measured": "2026-11-04",
                 "agents": {
                   "codex": {"model": "gpt-6.1-terra", "cacheWrite1h": false,
                     "question": {"tasks": 3, "graphCalls": 3, "input": 2000, "output": 0, "cacheRead": 0,
                                  "cacheWrite": 0,
                                  "sizes": [
                                    {"loc": 100, "tasks": 1, "graphCalls": 1, "input": 100, "output": 0,
                                     "cacheRead": 0, "cacheWrite": 0},
                                    {"loc": 10000, "tasks": 2, "graphCalls": 2, "input": 5000, "output": 0,
                                     "cacheRead": 0, "cacheWrite": 0}]}}}}
                """);
        Path small = Files.createDirectories(ws.resolve("small/src/main/java"));
        Files.writeString(small.resolve("A.java"), "class A {\n\n  int x;\n}\n".repeat(50));
        Path large = Files.createDirectories(ws.resolve("large/src/main/kotlin"));
        Files.writeString(large.resolve("B.kt"), "val b = 1\n".repeat(2_000));
        ProjectLines lines = new ProjectLines(rates.largest(), ProjectLinesTest.at(new AtomicLong(T)));
        assertThat(lines.of(ws.resolve("small").toString())).isEqualTo(150);
        assertThat(lines.of(ws.resolve("large").toString())).isEqualTo(2_000);

        List<SessionLog.Acted> actions = List.of(
                acted("usages", ActionRecord.OK, ws.resolve("small").toString(), 0, "p-small", "codex"),
                acted("find", ActionRecord.OK, ws.resolve("large").toString(), 0, "p-large", "codex"),
                acted("read", ActionRecord.OK, ws.resolve("gone").toString(), 0, "p-gone", "codex"));
        List<Savings> rows = SavingsLedger.fold(List.of(), actions, rates, benchModels(), lines, T - 1, T + 10);

        assertThat(rows.subList(1, rows.size()))
                .extracting(Savings::projectId, r -> r.tokens().total())
                .as("150 lines is nearest the 100-line repository, 2000 the 10000-line one by ratio; "
                        + "a checkout no longer there gets the rate over every task")
                .containsExactlyInAnyOrder(tuple("p-small", 100L), tuple("p-large", 5_000L), tuple("p-gone", 2_000L));
    }

    @Test
    void the_shipped_table_credits_every_agent_it_names_and_the_built_in_prices_know_claude_codes_model() {
        SavingsRates shipped = SavingsRates.shipped();
        assertThat(shipped.measured()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(shipped.agents().values())
                .allSatisfy(a -> assertThat(a.question().all().total()).isPositive());
        assertThat(shipped.agents().values())
                .as("every agent's question rate is measured by repository size, smallest first")
                .allSatisfy(a -> assertThat(a.question().sizes())
                        .isNotEmpty()
                        .isSortedAccordingTo(Comparator.comparingLong(SavingsRates.Sized::lines)));
        assertThat(shipped.largest()).isPositive();
        assertThat(shipped.agents().get("claude-code"))
                .satisfies(
                        a -> assertThat(PriceTable.builtIn().price(a.model())).isPresent());
    }

    @Test
    void the_rows_carry_the_tokens_by_model_so_rows_from_many_machines_add_up() {
        List<SessionLog.Acted> actions = List.of(acted("usages", ActionRecord.OK, "/ws/app", 0, "p-app", "codex"));
        Savings all = SavingsLedger.fold(List.of(), actions, RATES, benchModels(), ProjectLines.NONE, T - 1, T + 10)
                .getFirst();
        assertThat(all.toMap())
                .containsEntry("tokensSaved", 220_600L)
                .containsEntry("tokensUnpriced", 0L)
                .containsEntry(
                        "tokensSavedByModel",
                        List.of(Map.of(
                                "model", "gpt-6.1-terra",
                                "input", 20_000L,
                                "output", 600L,
                                "cacheRead", 200_000L,
                                "cacheWrite", 0L,
                                "cacheWrite1h", 0L)));
    }
}
