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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine's own house rules, as guard tests: a forecast key hashes the same facts as the build
 * key it predicts, and a spike-cache test roots its project in a {@code @TempDir}. Every
 * expectation is read out of the engine's source, never restated; a scan that comes back
 * implausibly small throws, so a blind arm is {@code scanner-failed}, never clean.
 */
@GuardSuite(scope = Scope.MODULE)
final class EngineRules {

    private static final String ENGINE_MAIN = "server/engine/src/main/java/**/*.java";

    /** One body, both callers: the owner site to the {@code <file>|<literal>} reach points. */
    private static final Map<String, List<String>> SHARED = Map.of(
            "PackagingKeys.java|jarKey",
                    List.of(
                            "package-jar",
                            "PlannerPackage.java|PackagingKeys.packageJar(",
                            "PlannerPackage.java|PackagingKeys.packageJar(jar, s.dir"),
            "PackagingKeys.java|asmKey",
                    List.of(
                            "package-assembly",
                            "PlannerTails.java|PackagingKeys.assembly(",
                            "ForecastPackagingTails.java|PackagingKeys.assemblyActionCached("),
            "PackagingKeys.java|pkgKey",
                    List.of(
                            "plugin packager",
                            "PlannerPlugin.java|PackagingKeys.pluginPackager(",
                            "PlannerPackage.java|PackagingKeys.pluginPackagerStep("),
            "PackagingKeys.java|jdKey",
                    List.of(
                            "package-javadoc",
                            "PlannerJavadoc.java|PackagingKeys.javadoc(",
                            "ForecastPackagingTails.java|PackagingKeys.javadoc("),
            "GuardKeys.java|key",
                    List.of(
                            "guard",
                            "PlannerGuards.java|GuardKeys.laneKey(",
                            "PlannerGuards.java|GuardKeys.forecastModuleLane(",
                            "PlannerGuards.java|GuardKeys.forecastWorkspaceLane(s.dir, s.actionCache)",
                            "PlannerGuards.java|GuardKeys.forecastTreeLane(s.dir, s.actionCache)",
                            "PlannerGuards.java|GuardKeys.forecastOutputLane(s.dir, s.actionCache)"));

    /** Sites with no forecast twin: task name, whether the forecast emits a step for it, and why. */
    private static final Map<String, Object[]> UNPAIRED = Map.of(
            "PlannerTails.java|key",
                    new Object[] {"package-sources", false, "explain does not forecast the sources jar at all"},
            "PlannerWar.java|key", new Object[] {"package-war", false, "explain does not forecast the war at all"},
            "PlannerNative.java|nKey",
                    new Object[] {
                        "native-image",
                        true,
                        "forecast replays PackagingKeys.nativeActionCached from the last record, not the live token bag"
                    },
            "PlannerPlugin.java|actionKey",
                    new Object[] {
                        "plugin-<step>",
                        false,
                        "a plugin step is priced by whether its module compiles, never by its key"
                    },
            "ImageWrite.java|imgKey", new Object[] {"write-image", true, "the image tail declares itself always run"},
            "BuildLogicSupport.java|key", new Object[] {"build-logic", false, "build-logic compile is not forecast"});

    private static final Map<String, List<String>> REQUEST_SHARED = Map.of(
            "PlannerCompile.java|public static CompileRequest mainCompileRequest(",
            List.of(
                    "compile-main",
                    "PlannerCompile.java|mainCompileRequest(new MainCompile(",
                    "PlannerCompileForecast.java|PlannerCompile.mainCompileRequest("),
            "PlannerCompile.java|public static CompileRequest testCompileRequest(",
            List.of(
                    "compile-test",
                    "TestSupport.java|PlannerCompile.testCompileRequest(compile)",
                    "PlannerTestForecast.java|PlannerCompile.testCompileRequest("),
            "PlannerFixtures.java|public static CompileRequest fixturesCompileRequest(",
            List.of(
                    "compile-test-fixtures",
                    "PlannerFixtures.java|CompileRequest request = fixturesCompileRequest(",
                    "PlannerFixtures.java|CompileRequest fxReq = fixturesCompileRequest("),
            "PlannerGuardSuite.java|public static CompileRequest guardCompileRequest(",
            List.of(
                    "compile-guard",
                    "PlannerGuardSuite.java|CompileRequest request = guardCompileRequest(",
                    "PlannerGuardSuite.java|CompileRequest req = guardCompileRequest("),
            "PlannerVersions.java|public static CompileRequest versionsCompileRequest(",
            List.of(
                    "compile-versions",
                    "PlannerVersions.java|CompileRequest request = versionsCompileRequest(",
                    "PlannerVersions.java|CompileRequest req = versionsCompileRequest("));

    private static final Map<String, Integer> REQUEST_SITES = Map.of(
            "PlannerCompile.java", 2,
            "PlannerFixtures.java", 1,
            "PlannerGuardSuite.java", 1,
            "PlannerVersions.java", 1,
            "LocalProjectBuilder.java", 1,
            "ScriptPlans.java", 1);

    /** Engine main sources by simple file name, comments blanked. */
    private static final class Sources {
        final Map<String, String> byName = new HashMap<>();
        final Map<String, String> pathOf = new HashMap<>();
        final List<String> all;

        Sources(Text text) {
            all = text.files(ENGINE_MAIN);
            if (all.size() < 330)
                throw new IllegalStateException(
                        "engine main sources: " + all.size() + "; measured against 368 — the walk moved");
            for (String rel : all) {
                String name = rel.substring(rel.lastIndexOf('/') + 1);
                byName.putIfAbsent(name, text.blanked(rel, Blank.COMMENTS));
                pathOf.putIfAbsent(name, rel);
            }
        }

        String read(String file) {
            String s = byName.get(file);
            if (s == null) throw new IllegalStateException("no " + file + " under server/engine/src/main/java");
            return s;
        }

        String path(String file) {
            return pathOf.getOrDefault(file, "server/engine/src/main/java/" + file);
        }
    }

    private static void fault(Violations v, String path, String key, String detail) {
        v.add(new TextSite(path, 0, key), detail);
    }

    private static void assertReaches(Sources src, Violations v, String reach, String label, String kind) {
        String file = reach.substring(0, reach.indexOf('|'));
        String literal = reach.substring(reach.indexOf('|') + 1);
        if (!src.read(file).contains(literal)) {
            fault(
                    v,
                    src.path(file),
                    reach,
                    label + " is declared a shared " + kind + " owner, but " + file + " no longer reaches it (`"
                            + literal + "` is gone)");
        }
    }

    @Guard(
            id = "forecast-key-parity",
            why =
                    "a forecast key that hashes a different fact set than the build key reports a phantom rebuild or blesses a stale artifact",
            instead =
                    "route the build and the forecast through one shared key owner; declare a site with no forecast twin, and why")
    void forecastKeyParity(Text text, Violations v) {
        Sources src = new Sources(text);
        // every forArtifact site is declared
        Pattern site = Pattern.compile("(?:String\\s+)?(\\w+)\\s*=\\s*ActionKey\\.forArtifact\\(");
        TreeSet<String> found = new TreeSet<>();
        for (String rel : src.all) {
            Matcher m = site.matcher(text.blanked(rel, Blank.NONE));
            while (m.find()) found.add(rel.substring(rel.lastIndexOf('/') + 1) + "|" + m.group(1));
        }
        TreeSet<String> declared = new TreeSet<>();
        declared.addAll(SHARED.keySet());
        declared.addAll(UNPAIRED.keySet());
        for (String f : found)
            if (!declared.contains(f))
                fault(
                        v,
                        src.path(f.substring(0, f.indexOf('|'))),
                        f,
                        "undeclared ActionKey.forArtifact site " + f + ": a key nobody has decided how to forecast");
        for (String d : declared)
            if (!found.contains(d))
                fault(
                        v,
                        src.path(d.substring(0, d.indexOf('|'))),
                        d,
                        "declared site " + d + " no longer exists; update the table");
        // shared owners really have both callers
        for (var e : SHARED.entrySet()) {
            List<String> spec = e.getValue();
            if (spec.size() - 1 < 2)
                fault(
                        v,
                        "server/engine/src/main/java",
                        e.getKey(),
                        spec.get(0) + " is declared shared but has fewer than two callers");
            for (String reach : spec.subList(1, spec.size()))
                assertReaches(src, v, reach, spec.get(0) + " (" + e.getKey() + ")", "key");
        }
        // unpaired keys are honest about whether the forecast steps them: a step the forecast emits
        // is one whose factory declares it priced or always run; the walk names its verdict after it.
        Map<String, String> taskNames = new HashMap<>();
        Matcher c = Pattern.compile("String\\s+([A-Z][A-Z_0-9]*)\\s*=\\s*\"([^\"]+)\"")
                .matcher(text.blanked("shared/jk-api/src/main/java/cc/jumpkick/run/TaskNames.java", Blank.NONE));
        while (c.find()) taskNames.put(c.group(1), c.group(2));
        TreeSet<String> steps = new TreeSet<>();
        Matcher declaredStep = Pattern.compile(
                        "Task\\.builder\\(TaskNames\\.([A-Z][A-Z_0-9]*)\\)\\s*\\.forecast\\(StepForecast\\.(?:always|priced)\\(")
                .matcher(String.join(
                        "\n",
                        src.all.stream()
                                .map(rel -> text.blanked(rel, Blank.COMMENTS))
                                .toList()));
        while (declaredStep.find()) {
            String resolved = taskNames.get(declaredStep.group(1));
            if (resolved != null) steps.add(resolved);
        }
        for (var e : UNPAIRED.entrySet()) {
            String taskName = (String) e.getValue()[0];
            boolean claims = (boolean) e.getValue()[1];
            if (steps.contains(taskName) != claims) {
                fault(
                        v,
                        src.path(e.getKey().substring(0, e.getKey().indexOf('|'))),
                        e.getKey(),
                        e.getKey() + " claims the forecast " + (claims ? "does" : "does not") + " emit a `" + taskName
                                + "` step; stated reason: " + e.getValue()[2]);
            }
        }
        // every keyed CompileRequest is built by one shared owner that both sides call
        Map<String, Integer> found2 = new HashMap<>();
        for (String rel : src.all) {
            int n = 0;
            Matcher b = Pattern.compile("CompileRequest\\.builder\\(\\)").matcher(text.blanked(rel, Blank.NONE));
            while (b.find()) n++;
            if (n > 0) found2.put(rel.substring(rel.lastIndexOf('/') + 1), n);
        }
        if (!found2.equals(REQUEST_SITES))
            fault(
                    v,
                    "server/engine/jk.toml",
                    "CompileRequest.builder() sites",
                    "every CompileRequest.builder() chain must be declared as keyed or unkeyed; found "
                            + new TreeSet<>(found2.keySet()) + ", declared " + new TreeSet<>(REQUEST_SITES.keySet()));
        for (var e : REQUEST_SHARED.entrySet()) {
            String ownerFile = e.getKey().substring(0, e.getKey().indexOf('|'));
            String ownerMarker = e.getKey().substring(e.getKey().indexOf('|') + 1);
            List<String> spec = e.getValue();
            if (!src.read(ownerFile).contains(ownerMarker))
                fault(
                        v,
                        src.path(ownerFile),
                        e.getKey(),
                        spec.get(0) + "'s shared CompileRequest owner is gone from " + ownerFile);
            for (String reach : spec.subList(1, spec.size()))
                assertReaches(src, v, reach, spec.get(0) + " (" + e.getKey() + ")", "CompileRequest");
        }
        v.population(src.all.size());
    }

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
