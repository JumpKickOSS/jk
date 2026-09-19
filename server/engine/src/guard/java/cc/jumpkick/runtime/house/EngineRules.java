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

    /** A build site and its forecast twin, addressed as {@code <file>|<key variable>}. */
    private static final List<String[]> PAIRS =
            List.<String[]>of(new String[] {"package-jar", "PlannerPackage.java|pkgKey", "ModuleForecast.java|pkgKey"});

    /** One body, both callers: the owner site to the {@code <file>|<literal>} reach points. */
    private static final Map<String, List<String>> SHARED = Map.of(
            "PackagingKeys.java|asmKey",
                    List.of(
                            "package-assembly",
                            "PlannerTails.java|PackagingKeys.assembly(",
                            "ForecastPackagingTails.java|PackagingKeys.assemblyActionCached("),
            "PackagingKeys.java|pkgKey",
                    List.of(
                            "plugin packager",
                            "PlannerPlugin.java|PackagingKeys.pluginPackager(",
                            "ModuleForecast.java|PackagingKeys.pluginPackagerStep("),
            "PackagingKeys.java|jdKey",
                    List.of(
                            "package-javadoc",
                            "PlannerJavadoc.java|PackagingKeys.javadoc(",
                            "ForecastPackagingTails.java|PackagingKeys.javadoc("),
            "GuardKeys.java|key",
                    List.of(
                            "guard",
                            "PlannerGuards.java|GuardKeys.laneKey(",
                            "ModuleForecast.java|GuardKeys.forecastModuleLane(",
                            "GuardKeys.java|forecastWorkspaceLane(root, actionCache)",
                            "GuardKeys.java|forecastTreeLane(root, actionCache)",
                            "GuardKeys.java|forecastOutputLane(root, actionCache)"));

    /** Sites with no forecast twin: task name, whether the forecast emits a step for it, and why. */
    private static final Map<String, Object[]> UNPAIRED = Map.of(
            "PlannerTails.java|key",
                    new Object[] {"package-sources", false, "explain does not forecast the sources jar at all"},
            "PlannerNative.java|nKey",
                    new Object[] {
                        "native-image",
                        true,
                        "forecast replays PackagingKeys.nativeActionCached from the last record, not the live token bag"
                    },
            "PlannerPlugin.java|actionKey",
                    new Object[] {"plugin-<step>", false, "plugin steps are not forecast (no step, no key)"},
            "ImageWrite.java|imgKey",
                    new Object[] {
                        "write-image", true, "the image tail is an unconditional side-effect step — always RUN"
                    },
            "BuildLogicSupport.java|key", new Object[] {"build-logic", false, "build-logic compile is not forecast"});

    private static final List<String> STEP_INDIRECTIONS = List.of("String name", "name");

    private static final Map<String, List<String>> REQUEST_SHARED = Map.of(
            "PlannerCompile.java|public static CompileRequest mainCompileRequest(",
            List.of(
                    "compile-main",
                    "PlannerCompile.java|mainCompileRequest(new MainCompile(",
                    "ModuleForecast.java|PlannerCompile.mainCompileRequest("),
            "PlannerCompile.java|public static CompileRequest testCompileRequest(",
            List.of(
                    "compile-test",
                    "TestSupport.java|PlannerCompile.testCompileRequest(compile)",
                    "ModuleForecast.java|PlannerCompile.testCompileRequest("),
            "PlannerFixtures.java|public static CompileRequest fixturesCompileRequest(",
            List.of(
                    "compile-test-fixtures",
                    "PlannerFixtures.java|CompileRequest request = fixturesCompileRequest(",
                    "PlannerFixtures.java|CompileRequest fxReq = fixturesCompileRequest("),
            "PlannerGuardSuite.java|public static CompileRequest guardCompileRequest(",
            List.of(
                    "compile-guard",
                    "PlannerGuardSuite.java|CompileRequest request = guardCompileRequest(",
                    "PlannerGuardSuite.java|CompileRequest req = guardCompileRequest("));

    private static final Map<String, Integer> REQUEST_SITES = Map.of(
            "PlannerCompile.java", 2,
            "PlannerFixtures.java", 1,
            "PlannerGuardSuite.java", 1,
            "LocalProjectBuilder.java", 1,
            "ScriptPlans.java", 1);

    private static final String BAG = "List<String> tokens = List.of(";

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

    private static String balancedFrom(String src, char open, char close, int at) {
        int depth = 0;
        boolean inStr = false;
        boolean inChar = false;
        boolean esc = false;
        for (int i = at; i < src.length(); i++) {
            char c = src.charAt(i);
            if (esc) {
                esc = false;
            } else if (inStr || inChar) {
                if (c == '\\') esc = true;
                else if (inStr && c == '"') inStr = false;
                else if (inChar && c == '\'') inChar = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return src.substring(at + 1, i);
            }
        }
        throw new IllegalStateException("unbalanced '" + open + "' at offset " + at);
    }

    private static List<String> splitTopLevel(String args) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean inStr = false;
        boolean inChar = false;
        boolean esc = false;
        for (char c : args.toCharArray()) {
            if (esc) {
                esc = false;
            } else if (inStr || inChar) {
                if (c == '\\') esc = true;
                else if (inStr && c == '"') inStr = false;
                else if (inChar && c == '\'') inChar = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '\'') inChar = true;
                else if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') depth--;
                else if (c == ',' && depth == 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                    continue;
                }
            }
            sb.append(c);
        }
        if (!sb.toString().isBlank()) out.add(sb.toString());
        return out;
    }

    /** The {@code "prefix:"} literals opening each token of {@code siteId}'s bag; faults go to {@code v}. */
    private static TreeSet<String> tokenPrefixes(Sources src, String siteId, Violations v) {
        String file = siteId.substring(0, siteId.indexOf('|'));
        String keyVar = siteId.substring(siteId.indexOf('|') + 1);
        String code = src.read(file);
        TreeSet<String> out = new TreeSet<>();
        int callAt = code.indexOf("String " + keyVar + " = ActionKey.forArtifact(");
        if (callAt < 0) {
            fault(v, src.path(file), siteId, "declared site " + siteId + " no longer exists; update the table");
            return out;
        }
        int bagAt = code.lastIndexOf(BAG, callAt);
        if (bagAt < 0) {
            fault(
                    v,
                    src.path(file),
                    siteId,
                    siteId + " no longer keys a `" + BAG
                            + "…)` bag — a bag this cannot read is a bag it cannot compare");
            return out;
        }
        String between = code.substring(bagAt, callAt);
        if (between.contains("tokens.add") || between.contains("ActionKey.forArtifact(")) {
            fault(
                    v,
                    src.path(file),
                    siteId,
                    siteId
                            + "'s token bag is amended between its List.of and its key, so the literal list is not the whole fact set");
        }
        Pattern prefix = Pattern.compile("^\"([A-Za-z][A-Za-z0-9_.-]*):");
        for (String raw : splitTopLevel(balancedFrom(code, '(', ')', bagAt + BAG.length() - 1))) {
            String element = raw.strip();
            Matcher m = prefix.matcher(element);
            if (!m.find()) {
                fault(
                        v,
                        src.path(file),
                        siteId + " " + element,
                        siteId
                                + " token does not open with a \"prefix:\" literal, so no side can be compared against it: "
                                + element);
                continue;
            }
            out.add(m.group(1));
        }
        return out;
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
                    "pair the site with its forecast twin, or route both through one shared owner; declare an unpaired site with the reason there is no twin")
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
        for (String[] p : PAIRS) {
            declared.add(p[1]);
            declared.add(p[2]);
        }
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
        // paired keys hash the same prefixes
        for (String[] p : PAIRS) {
            TreeSet<String> a = tokenPrefixes(src, p[1], v);
            TreeSet<String> b = tokenPrefixes(src, p[2], v);
            if (!a.equals(b))
                fault(
                        v,
                        src.path(p[2].substring(0, p[2].indexOf('|'))),
                        p[0],
                        p[0] + ": the build hashes " + a + " and the forecast " + b
                                + ", so jk explain reports a phantom rebuild or misses a real one");
        }
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
        // unpaired keys are honest about whether the forecast steps them
        // The module forecast emits its steps from ModuleForecast; the shared step factory stays in
        // TaskForecaster, so both are scanned.
        String forecaster = src.read("TaskForecaster.java") + "\n" + src.read("ModuleForecast.java");
        TreeSet<String> stepArgs = new TreeSet<>();
        for (Pattern p : List.of(
                Pattern.compile("new TaskForecast\\.Task\\(\\s*([^,]+),"),
                Pattern.compile("\\bcompileStep\\(\\s*([^,]+),"))) {
            Matcher m = p.matcher(forecaster);
            while (m.find()) stepArgs.add(m.group(1).strip().replaceAll("\\s+", " "));
        }
        for (String a : stepArgs) {
            if (!a.startsWith("\"") && !a.startsWith("TaskNames.") && !STEP_INDIRECTIONS.contains(a)) {
                fault(
                        v,
                        src.path("TaskForecaster.java"),
                        "step " + a,
                        "TaskForecaster names a step through something this scan cannot resolve: " + a);
            }
        }
        Map<String, String> taskNames = new HashMap<>();
        Matcher c = Pattern.compile("String\\s+([A-Z][A-Z_0-9]*)\\s*=\\s*\"([^\"]+)\"")
                .matcher(text.blanked("shared/jk-api/src/main/java/cc/jumpkick/run/TaskNames.java", Blank.NONE));
        while (c.find()) taskNames.put(c.group(1), c.group(2));
        TreeSet<String> steps = new TreeSet<>();
        for (String arg : stepArgs) {
            if (arg.startsWith("\"")) steps.add(arg.replace("\"", ""));
            else if (arg.startsWith("TaskNames.")) {
                String resolved = taskNames.get(arg.substring("TaskNames.".length()));
                if (resolved == null)
                    fault(v, src.path("TaskForecaster.java"), arg, "TaskNames has no constant " + arg);
                else steps.add(resolved);
            }
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
