// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.house;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.ArrayList;
import java.util.List;

/**
 * The engine's own house rules, as guard tests: every module plan step declares how it is
 * forecast, and a spike-cache test roots its project in a {@code @TempDir}. Every expectation is
 * read out of the engine's source, never restated; a scan that comes back implausibly small
 * throws, so a blind arm is {@code scanner-failed}, never clean. The forecast-key rules are
 * {@link ForecastKeyRules}.
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
        String exempt = "server/engine/src/test/java/cc/jumpkick/runtime/NiaScratchTest.java";
        if (text.lines(exempt).isEmpty())
            throw new IllegalStateException("the one exemption " + exempt + " is gone; the rule points at nothing");
        List<String> naming = new ArrayList<>();
        for (String rel : text.files("server/engine/src/test/java/**/*.java")) {
            if (text.blanked(rel, Blank.NONE).contains(marker)) naming.add(rel);
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
}
