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
            {"published": "2026-11-04T00:00:00Z", "measured": "2026-11-04",
             "rates": [
               {"agent": "claude-code", "model": "claude-sonnet-5-5", "effort": "medium", "measured": "2026-11-04",
                "cacheWrite1h": true,
                "question": {"tasks": 8, "graphCalls": 10, "input": 10, "output": 1000, "cacheRead": 200000,
                             "cacheWrite": 20000},
                "rename": {"tasks": 7, "graphCalls": 7, "input": 5, "output": 500, "cacheRead": 50000,
                           "cacheWrite": 5000}},
               {"agent": "codex", "model": "gpt-6.1-terra", "effort": "medium", "measured": "2026-11-03",
                "cacheWrite1h": false,
                "question": {"tasks": 8, "graphCalls": 9, "input": 20000, "output": 600, "cacheRead": 200000,
                             "cacheWrite": 0}}]}
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
                                acted("usages", ActionRecord.OK, "/ws", 0, null).action(), SavingsRates.Credit.NONE)
                        .graphCalls())
                .isEqualTo(1);
        assertThat(SavingsLedger.of(
                                acted("rename", ActionRecord.OK, "/ws", 0, null).action(), SavingsRates.Credit.NONE)
                        .refactorings())
                .isEqualTo(1);
        assertThat(SavingsLedger.of(
                                acted("deps", ActionRecord.OK, "/ws", 2, null).action(), SavingsRates.Credit.NONE)
                        .edits())
                .isEqualTo(2);
        Savings failed = SavingsLedger.of(
                acted("query", ActionRecord.ERROR, "/ws", 0, null).action(), SavingsRates.Credit.NONE);
        assertThat(failed.nothing()).isTrue();
        Savings failedRename = SavingsLedger.of(
                acted("rename", ActionRecord.ERROR, "/ws", 0, null).action(), SavingsRates.Credit.NONE);
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
                        "ratesMeasured",
                        "ratesMatched",
                        "pricesRetrieved")
                .containsEntry("scope", "all")
                .containsEntry("since", 5L);
    }

    @Test
    void a_question_and_a_refactoring_save_their_agents_tokens_per_graph_call_kept_by_model() {
        TokensSaved.Counts question = new TokensSaved.Counts(10, 1000, 200_000, 0, 20_000);
        TokensSaved.Counts rename = new TokensSaved.Counts(5, 500, 50_000, 0, 5_000);
        TokensSaved.Counts codexQuestion = new TokensSaved.Counts(20_000, 600, 200_000, 0, 0);
        assertThat(RATES.saved(acted("usages", ActionRecord.OK, "/ws", 0, null).action(), "claude-code", null, null, 0)
                        .tokens()
                        .byModel())
                .as("Claude Code writes its cache for an hour")
                .containsEntry("claude-sonnet-5-5", question);
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
        TokensSaved onOpus =
                RATES.saved(usages, "claude-code", "claude-opus-5-5", null, 0).tokens();
        assertThat(onOpus.byModel()).containsOnlyKeys("claude-opus-5-5");
        assertThat(onOpus.priced(PRICES).usdMicros())
                .as("the bench's tokens at the model jk read the agent was on: Opus, not Sonnet")
                .isEqualTo(40 + 20_000 + 40_000 + 160_000);
        assertThat(onOpus.priced(PRICES).retrieved()).hasToString("2026-11-16");
        TokensSaved unknown =
                RATES.saved(usages, "claude-code", "claude-next-7", null, 0).tokens();
        assertThat(unknown.priced(PRICES)).isEqualTo(new TokensSaved.Priced(0, unknown.total(), null));
        assertThat(unknown.text(PRICES))
                .isEqualTo("~221k, ~$0.000 (estimate, API list prices; 221k on models with no price)");
        assertThat(onOpus.plus(unknown).text(PRICES))
                .isEqualTo("~442k, ~$0.220 (estimate, API list prices of 2026-11-16; 221k on models with no price)");
    }

    /** Three agents on two models at two efforts, one rate each, so each step's pick shows in the input. */
    static final SavingsRates STEPS = SavingsRates.parse("""
            {"published": "2026-11-20T00:00:00Z", "measured": "2026-11-20",
             "rates": [
               {"agent": "claude-code", "model": "claude-opus-5-5", "effort": "medium", "measured": "2026-11-20",
                "cacheWrite1h": true, "question": {"input": 100}, "rename": {"input": 1000}},
               {"agent": "claude-code", "model": "claude-sonnet-5-5", "effort": "low", "measured": "2026-11-19",
                "cacheWrite1h": true, "question": {"input": 200}},
               {"agent": "cursor", "model": "claude-opus-5-5", "effort": "high", "measured": "2026-11-18",
                "question": {"input": 300}},
               {"agent": "codex", "model": "gpt-6.1-terra", "effort": "medium", "measured": "2026-11-17",
                "question": {"input": 700}}]}
            """);

    private static SavingsRates.Credit question(
            SavingsRates rates, @Nullable String client, @Nullable String model, @Nullable String effort) {
        return rates.saved(acted("query", ActionRecord.OK, "/ws", 0, null).action(), client, model, effort, 0);
    }

    private static SavingsRates.Match match(SavingsRates.Credit c) {
        return Objects.requireNonNull(c.match());
    }

    private static long input(SavingsRates.Credit c) {
        return c.tokens().byModel().values().stream()
                .mapToLong(TokensSaved.Counts::input)
                .sum();
    }

    @Test
    void a_session_is_priced_by_the_closest_measurement_in_five_steps_and_a_tie_takes_the_median() {
        SavingsRates.Credit exact = question(STEPS, "claude-code", "claude-opus-5-5", "medium");
        assertThat(exact.match())
                .isEqualTo(
                        new SavingsRates.Match(1, List.of("claude-code/claude-opus-5-5/medium/2026-11-20"), List.of()));
        assertThat(input(exact)).isEqualTo(100);

        SavingsRates.Credit modelEffort = question(STEPS, "cursor-agent", "claude-opus-5-5", "medium");
        assertThat(modelEffort.match())
                .extracting(SavingsRates.Match::step, SavingsRates.Match::name)
                .containsExactly(2, "model-effort");
        assertThat(input(modelEffort))
                .as("cursor on Opus at medium: Claude Code's Opus medium")
                .isEqualTo(100);

        SavingsRates.Credit model = question(STEPS, "antigravity", "claude-opus-5-5", "xhigh");
        assertThat(match(model).step()).isEqualTo(3);
        assertThat(match(model).measurements())
                .containsExactly(
                        "claude-code/claude-opus-5-5/medium/2026-11-20", "cursor/claude-opus-5-5/high/2026-11-18");
        assertThat(input(model))
                .as("every Opus measurement, the mean of the middle two")
                .isEqualTo(200);

        SavingsRates.Credit agent = question(STEPS, "Claude Code", "claude-haiku-5-5", "low");
        assertThat(match(agent).step()).isEqualTo(4);
        assertThat(input(agent))
                .as("Claude Code's two measurements on other models")
                .isEqualTo(150);
        assertThat(agent.tokens().byModel()).as("priced at the model it was on").containsOnlyKeys("claude-haiku-5-5");

        SavingsRates.Credit guess = question(STEPS, "some-new-agent", "a-new-model", "medium");
        assertThat(guess.match())
                .extracting(SavingsRates.Match::step, SavingsRates.Match::name)
                .containsExactly(5, "best-guess");
        assertThat(match(guess).measurements()).hasSize(4);
        assertThat(input(guess)).as("the median over every measurement").isEqualTo(250);

        SavingsRates.Credit rename = renameCredit(STEPS, "some-new-agent");
        assertThat(match(rename).measurements())
                .as("only measurements with a rename rate price a rename")
                .containsExactly("claude-code/claude-opus-5-5/medium/2026-11-20");
    }

    private static SavingsRates.Credit renameCredit(SavingsRates rates, String client) {
        return rates.saved(acted("rename", ActionRecord.OK, "/ws", 0, null).action(), client, null, null, 0);
    }

    @Test
    void a_field_jk_cannot_read_skips_the_steps_that_need_it_and_the_match_says_so() {
        SavingsRates.Credit noEffort = question(STEPS, "claude-code", "claude-opus-5-5", null);
        assertThat(match(noEffort).step())
                .as("no effort: steps 1 and 2 are skipped")
                .isEqualTo(3);
        assertThat(match(noEffort).unknown()).containsExactly("effort");

        SavingsRates.Credit noModel = question(STEPS, "claude-code", null, null);
        assertThat(match(noModel).step()).isEqualTo(4);
        assertThat(match(noModel).unknown()).containsExactly("model", "effort");
        assertThat(noModel.tokens().byModel())
                .as("no model read: priced at the first matched measurement's model, its cache writes for an hour")
                .containsOnlyKeys("claude-opus-5-5")
                .containsEntry("claude-opus-5-5", new TokensSaved.Counts(150, 0, 0, 0, 0));

        SavingsRates.Credit nothing = question(STEPS, null, null, null);
        assertThat(match(nothing).step()).isEqualTo(5);
        assertThat(match(nothing).unknown()).containsExactly("agent", "model", "effort");
    }

    @Test
    void dated_or_suffixed_ids_of_one_model_match() {
        assertThat(SavingsRates.normalized("claude-opus-5-5-20261101")).isEqualTo("claude-opus-5-5");
        assertThat(SavingsRates.normalized("Claude-Opus-5.5")).isEqualTo("claude-opus-5-5");
        assertThat(SavingsRates.normalized("anthropic/claude-opus-5-5@20261101"))
                .isEqualTo("claude-opus-5-5");
        assertThat(SavingsRates.normalized("claude-opus-5-5[1m]")).isEqualTo("claude-opus-5-5");
        assertThat(SavingsRates.normalized("gpt-6.1-terra-2026-10-01")).isEqualTo("gpt-6-1-terra");
        assertThat(SavingsRates.normalized("gpt-6.1-terra-latest")).isEqualTo("gpt-6-1-terra");
        assertThat(SavingsRates.normalized("claude-sonnet-5-5"))
                .isNotEqualTo(SavingsRates.normalized("claude-opus-5-5"));
        assertThat(match(question(STEPS, "claude-code", "claude-opus-5.5-20261101", "Medium"))
                        .step())
                .isEqualTo(1);
    }

    @Test
    void every_code_answer_and_refactoring_is_credited_and_the_rows_say_which_measurements_priced_them() {
        ActionRecord rename = acted("rename", ActionRecord.OK, "/ws", 0, null).action();
        assertThat(RATES.agentOf("Codex-MCP-Client")).isEqualTo("codex");
        assertThat(RATES.agentOf("Claude Code")).isEqualTo("claude-code");
        assertThat(SavingsRates.shipped().agentOf("antigravity-cli")).isEqualTo("agy");
        assertThat(RATES.agentOf("Some-New-Agent"))
                .as("its own name, which matches no measurement")
                .isEqualTo("somenewagent");
        assertThat(RATES.agentOf(null)).isNull();
        assertThat(RATES.saved(rename, "codex", null, null, 0).match())
                .as("the bench has no codex rename: Claude Code's is the only one, a best guess")
                .extracting(SavingsRates.Match::step)
                .isEqualTo(5);
        assertThat(RATES.saved(
                        acted("rename", ActionRecord.ERROR, "/ws", 0, null).action(), "codex", null, null, 0))
                .as("a failed call saves nothing")
                .isEqualTo(SavingsRates.Credit.NONE);
        assertThat(RATES.saved(acted("deps", ActionRecord.OK, "/ws", 1, null).action(), "codex", null, null, 0))
                .as("the bench measures no tokens for a dependency edit")
                .isEqualTo(SavingsRates.Credit.NONE);

        List<SessionLog.Acted> actions = List.of(
                acted("usages", ActionRecord.OK, "/ws/app", 0, "p-app", "claude-code"),
                acted("usages", ActionRecord.OK, "/ws/app", 0, "p-app", "some-new-agent"),
                acted("query", ActionRecord.OK, "/ws/lib", 0, "p-lib", "some-new-agent"),
                acted("query", ActionRecord.ERROR, "/ws/lib", 0, "p-lib", "some-new-agent"));
        Savings all = SavingsLedger.fold(List.of(), actions, RATES, benchModels(), ProjectLines.NONE, T - 1, T + 10)
                .getFirst();

        assertThat(all.graphCalls()).isEqualTo(3);
        assertThat(all.tokens().total())
                .as("an agent the bench never ran is priced too")
                .isPositive();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matched =
                (List<Map<String, Object>>) Objects.requireNonNull(all.toMap().get("ratesMatched"));
        assertThat(matched)
                .extracting(m -> m.get("step"), m -> m.get("match"), m -> m.get("calls"))
                .containsExactly(tuple(5, "best-guess", 2L), tuple(4, "agent", 1L));
        assertThat(matched.getFirst())
                .containsEntry(
                        "measurements",
                        List.of(
                                "claude-code/claude-sonnet-5-5/medium/2026-11-04",
                                "codex/gpt-6.1-terra/medium/2026-11-03"))
                .containsEntry("unknown", List.of("model", "effort"));
        assertThat(all.tokens().text(PRICES)).doesNotContain("measured").startsWith("~");
    }

    @Test
    void a_total_is_priced_from_the_recorded_actions_with_the_rates_in_effect_when_read() {
        List<SessionLog.Acted> actions = List.of(acted("usages", ActionRecord.OK, "/ws/app", 0, "p-app", "agy"));
        long before = SavingsLedger.fold(List.of(), actions, RATES, benchModels(), ProjectLines.NONE, T - 1, T + 10)
                .getFirst()
                .tokens()
                .total();
        SavingsRates later = SavingsRates.parse("""
                {"published": "2026-12-01T00:00:00Z", "measured": "2026-12-01",
                 "rates": [{"agent": "agy", "model": "gemini-4-pro", "effort": "high", "measured": "2026-12-01",
                            "question": {"input": 7}}]}
                """);
        Savings after = SavingsLedger.fold(List.of(), actions, later, benchModels(), ProjectLines.NONE, T - 1, T + 10)
                .getFirst();
        assertThat(after.tokens().byModel())
                .as("the same recorded call, re-priced by a table that measured its agent")
                .containsExactly(Map.entry("gemini-4-pro", new TokensSaved.Counts(7, 0, 0, 0, 0)));
        assertThat(before).isNotEqualTo(7);
    }

    @Test
    void a_call_is_credited_the_rate_measured_in_the_repository_nearest_its_projects_size(@TempDir Path ws)
            throws IOException {
        SavingsRates rates = SavingsRates.parse("""
                {"published": "2026-11-04T00:00:00Z", "measured": "2026-11-04",
                 "rates": [
                   {"agent": "codex", "model": "gpt-6.1-terra", "effort": "medium", "measured": "2026-11-04",
                    "question": {"tasks": 3, "graphCalls": 3, "input": 2000, "output": 0, "cacheRead": 0,
                                 "cacheWrite": 0,
                                 "sizes": [
                                   {"loc": 100, "tasks": 1, "graphCalls": 1, "input": 100, "output": 0,
                                    "cacheRead": 0, "cacheWrite": 0},
                                   {"loc": 10000, "tasks": 2, "graphCalls": 2, "input": 5000, "output": 0,
                                    "cacheRead": 0, "cacheWrite": 0}]}}]}
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
        assertThat(shipped.published()).isNotNull();
        assertThat(shipped.rates())
                .allSatisfy(a -> assertThat(a.question().all().total()).isPositive());
        assertThat(shipped.rates()).as("every agent, model and effort is named").allSatisfy(a -> assertThat(
                        List.of(a.agent(), a.model(), a.effort(), a.measured()))
                .allSatisfy(f -> assertThat(f).isNotBlank()));
        assertThat(shipped.rates())
                .as("every agent's question rate is measured by repository size, smallest first")
                .allSatisfy(a -> assertThat(a.question().sizes())
                        .isNotEmpty()
                        .isSortedAccordingTo(Comparator.comparingLong(SavingsRates.Sized::lines)));
        assertThat(shipped.largest()).isPositive();
        assertThat(shipped.rates())
                .filteredOn(a -> a.agent().equals("claude-code"))
                .isNotEmpty()
                .allSatisfy(
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
