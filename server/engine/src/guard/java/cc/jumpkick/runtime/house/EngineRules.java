// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.house;

import cc.jumpkick.guard.api.Allow;
import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.task.KeyContract;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine's own house rules, as guard tests: every module plan step declares how it is
 * forecast, and a spike-cache test roots its project in a {@code @TempDir}. Every expectation is
 * read out of the engine's source, never restated; a scan that comes back implausibly small
 * throws, so a blind arm is {@code scanner-failed}, never clean. The forecast-key rules are
 * {@link ForecastKeyRules}. Code that picks a suite's classes or reads its outcome lives where the
 * run-tests stamp keys it ({@link KeyContract#SUITE}).
 */
@GuardSuite(scope = Scope.MODULE)
final class EngineRules {

    private static final String ENGINE_MAIN = "server/engine/src/main/java/**/*.java";

    /** Plans no module build runs: the forecast never sees their steps. */
    private static final List<String> NOT_MODULE_PLANS = List.of(
            "AuditPlans.java",
            "CachePlans.java",
            "CompatPlans.java",
            "FormatPlans.java",
            "LockPlans.java",
            "ScriptPlans.java",
            "SyncPlans.java",
            "ToolPlans.java");

    @Guard(
            id = "steps-declare-forecast",
            why =
                    "a step that does not say how it is forecast is a step jk explain and the scheduler never see: a clean tree skips it, or a stale one never stops rebuilding",
            instead = "declare .forecast(StepForecast.PRICED / RIDES / always(why)) on the step's Task.builder chain")
    void stepsDeclareForecast(Text text, Violations v) {
        int sites = 0;
        for (String rel : text.files(ENGINE_MAIN)) {
            if (NOT_MODULE_PLANS.contains(rel.substring(rel.lastIndexOf('/') + 1))) continue;
            String code = text.blanked(rel, Blank.COMMENTS);
            int at = code.indexOf("Task.builder(");
            while (at >= 0) {
                sites++;
                int next = code.indexOf("Task.builder(", at + 1);
                int end = code.indexOf(".build()", at);
                String chain = code.substring(at, end < 0 ? code.length() : end);
                if (!chain.contains(".forecast(")) {
                    int line = (int) code.substring(0, at)
                                    .chars()
                                    .filter(c -> c == '\n')
                                    .count()
                            + 1;
                    v.add(
                            new TextSite(rel, line, "Task.builder"),
                            "this step declares no StepForecast, so the forecast cannot say whether it runs");
                }
                at = next;
            }
        }
        if (sites < 50)
            throw new IllegalStateException(
                    "module plan steps found: " + sites + " — 68 when measured; the walk moved");
        v.population(sites);
    }

    @Guard(
            id = "spike-cache-temp-dir",
            why =
                    "the spike cache is keyed by the project's absolute path; a test on a fixed root replays the stored record and the code under test never runs",
            instead = "root the test's project in a per-method @TempDir")
    void spikeCacheTempDir(Text text, Violations v) {
        String marker = "android-spike-cache";
        String exempt = "e2e/engine/src/test/java/cc/jumpkick/runtime/NiaScratchTest.java";
        if (text.lines(exempt).isEmpty())
            throw new IllegalStateException("the one exemption " + exempt + " is gone; the rule points at nothing");
        List<String> naming = new ArrayList<>();
        for (String tree : List.of("server/engine/src/test/java/**/*.java", "e2e/engine/src/test/java/**/*.java")) {
            for (String rel : text.files(tree)) {
                if (text.blanked(rel, Blank.NONE).contains(marker)) naming.add(rel);
            }
        }
        if (naming.size() < 10)
            throw new IllegalStateException("files naming \"" + marker + "\": " + naming.size()
                    + " — 18 when measured; a renamed marker or a moved tree");
        for (String rel : naming) {
            if (rel.equals(exempt)) continue;
            if (!text.blanked(rel, Blank.NONE).contains("@TempDir"))
                v.add(
                        new TextSite(rel, 0, "no @TempDir"),
                        "a spike-cache test without a per-method @TempDir replays the stored record");
        }
        v.population(naming.size());
    }

    private static final String ENGINE_SOURCES = "server/engine/src/main/java/";

    /** The types that pick a suite's classes, launch its JVM or read its outcome. */
    private static final Pattern SUITE_TYPES = Pattern.compile(
            "\\b(?:TestSelection|SuiteSelection|SuiteFilter|JUnitClassFilter|ClassTags|TestClassMatch|TestLaunch|ResultAggregator)\\b");

    @Guard(
            id = "suite-code-keyed",
            why =
                    "a run-tests stamp keys the engine by the code that picks, launches and judges a suite (KeyContract.SUITE); a class outside it that does so can change a suite's outcome while its green marker still replays",
            instead =
                    "move the code into cc.jumpkick.test or a class named for tests or suites, or allow it here when it only builds the selection the stamp keys, plans, schedules or reports")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/engine/http/mcp/McpReads.java",
            reason = "builds a selection from an MCP request; the stamp keys the selection itself")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/engine/jobs/JobSelect.java",
            reason = "builds a selection from a job's arguments; the stamp keys the selection itself")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/engine/verbs/WorkspaceBuildVerb.java",
            reason = "hands the request's selection to the session; the stamp keys the selection itself")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/EffortWeights.java",
            reason = "names the step a suite's history is kept under, to weigh work; it schedules")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/ForecastScope.java",
            reason = "the forecast: it says whether a suite runs and runs none")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/MemoInputs.java",
            reason = "keys the preflight memo, which folds the whole engine's code identity")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/NodeArm.java",
            reason =
                    "Node.js test steps, keyed as artifacts by the code KeyContract.ARTIFACT records, this file with it")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/PlannerResources.java",
            reason = "decides whether a suite step is planned at all; a step not planned stores no marker")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/SharedStampKeys.java",
            reason = "derives the shared form of the stamp's key; it picks no class and reads no outcome")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/TestStampInputs.java",
            reason = "writes the stamp itself; what it changes reaches a suite only through the key it writes")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/base/RecentClassWalls.java",
            reason = "the classes' last walls, which spread classes over workers; it picks none")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/workspace/NativePlans.java",
            reason = "turns a run's diagnostic into an exit code, after the marker is stored")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/workspace/StepEta.java",
            reason = "estimates a step's time; it runs nothing")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/workspace/WorkspaceFinalPhase.java",
            reason = "reads the build's verdict for its exit code, after every marker is stored")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/workspace/WorkspacePreparePhase.java",
            reason = "names the step and tier a suite's history is kept under, to schedule")
    @Allow(
            in = "server/engine/src/main/java/cc/jumpkick/runtime/workspace/WorkspaceResourcePhase.java",
            reason = "names the step and tier a suite's history is kept under, to size the run")
    void suiteCodeKeyed(Text text, Violations v) {
        int naming = 0;
        for (String rel : text.files(ENGINE_MAIN)) {
            String code = text.blanked(rel, Blank.COMMENTS_AND_STRINGS);
            Matcher m = SUITE_TYPES.matcher(code);
            if (!m.find()) continue;
            naming++;
            String entry = rel.substring(ENGINE_SOURCES.length(), rel.length() - ".java".length()) + ".class";
            if (KeyContract.suiteCode(entry)) continue;
            int line = (int) code.substring(0, m.start())
                            .chars()
                            .filter(c -> c == '\n')
                            .count()
                    + 1;
            v.add(
                    new TextSite(rel, line, m.group()),
                    "this class uses " + m.group() + " outside the code a run-tests stamp keys (KeyContract.SUITE)");
        }
        if (naming < 30)
            throw new IllegalStateException(
                    "engine sources naming a suite type: " + naming + " — 44 when measured; the walk moved");
        v.population(naming);
    }
}
