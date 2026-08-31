// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.SourceText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A forecast key hashes the same facts as the build key it predicts.
 *
 * <p>{@code jk explain} re-derives every cache key the build computes. When the two derivations
 * disagree the forecast either reports a phantom rebuild (the visible symptom) or blesses a stale
 * artifact (the dangerous one). Six such drifts were live at once, and the two tests that claimed
 * to guard this compared two hand-typed literals <em>in the test file</em> against each other
 * instead of reading the real bags — which is exactly why they stayed green through all six. So
 * every expectation here is read out of the engine's own source.
 *
 * <p>Every {@code ActionKey.forArtifact} site must appear in exactly one of three tables, so a new
 * key cannot be added without deciding how it is forecast.
 */
class ForecastKeyParityTest {

    /** A build site and its forecast twin, addressed as {@code <file>|<key variable>}. */
    private static final List<String[]> PAIRS =
            List.<String[]>of(new String[] {"package-jar", "PlannerPackage.java|pkgKey", "TaskForecaster.java|pkgKey"});

    /**
     * One body, both callers: {@code <owner site>} to the {@code <file>|<literal>} reach points that
     * prove each side goes through the owner rather than round it.
     *
     * <p>A prefix set cannot see a value drift behind an agreed prefix, and widening the scan to
     * values is not possible — values legitimately differ per module. So the durable answer is one
     * owner, and what is checked is that the owner really has both callers. A "shared" key with one
     * caller is an unpaired key wearing the word.
     */
    private static final Map<String, List<String>> SHARED = Map.of(
            "PackagingKeys.java|asmKey",
                    List.of(
                            "package-assembly",
                            "PlannerTails.java|PackagingKeys.assembly(",
                            "TaskForecaster.java|PackagingKeys.assemblyActionCached("),
            "PackagingKeys.java|pkgKey",
                    List.of(
                            "plugin packager",
                            "PlannerPlugin.java|PackagingKeys.pluginPackager(",
                            "TaskForecaster.java|PackagingKeys.pluginPackagerStep("));

    /**
     * Sites with no forecast twin: task name, whether the forecast emits a <em>step</em> for it, and
     * why there is nothing to compare.
     *
     * <p>Those last two are different claims, and the difference is where the last defect lived: a
     * site exempted as "not forecast" was true of the key and false of the step, so explain priced a
     * step against a key those builds never compute. The flag is checked against TaskForecaster.
     */
    private static final Map<String, Object[]> UNPAIRED = Map.of(
            "PlannerTails.java|key",
                    new Object[] {"package-sources", false, "explain does not forecast the sources jar at all"},
            "PlannerNative.java|nKey",
                    new Object[] {"native-image", true, "the forecast probes the task pointer, not a token bag"},
            "PlannerPlugin.java|actionKey",
                    new Object[] {"plugin-<step>", false, "plugin steps are not forecast (no step, no key)"},
            "ImageWrite.java|imgKey",
                    new Object[] {
                        "write-image", true, "the image tail is an unconditional side-effect step — always RUN"
                    },
            "BuildLogicSupport.java|key", new Object[] {"build-logic", false, "build-logic compile is not forecast"});

    /**
     * {@code compileStep} is the one forecast helper naming its step from a parameter; both call
     * sites pass a literal. Any OTHER unresolvable step name is a step the scan cannot see.
     */
    private static final List<String> STEP_INDIRECTIONS = List.of("String name", "name");

    /**
     * A keyed {@code CompileRequest} chain, as {@code <file>|<marker>}. The marker must occur
     * exactly once in its file; the chain is the {@code CompileRequest.builder()} nearest to it.
     */
    private static final List<String[]> REQUEST_PAIRS = List.<String[]>of(new String[] {
        "compile-test",
        "TestSupport.java|qualifiedTaskId(taskId, outputDir)",
        "TaskForecaster.java|TaskNames.COMPILE_TEST, testOut)"
    });

    /** A CompileRequest with one body both sides call. */
    private static final Map<String, List<String>> REQUEST_SHARED = Map.of(
            "PlannerCompile.java|public static CompileRequest mainCompileRequest(",
            List.of(
                    "compile-main",
                    "PlannerCompile.java|mainCompileRequest(new MainCompile(",
                    "TaskForecaster.java|PlannerCompile.mainCompileRequest("),
            "PlannerFixtures.java|public static CompileRequest fixturesCompileRequest(",
            List.of(
                    "compile-test-fixtures",
                    "PlannerFixtures.java|CompileRequest request = fixturesCompileRequest(",
                    "PlannerFixtures.java|CompileRequest fxReq = fixturesCompileRequest("));

    /** Every builder site and its count, so a new chain must be classified before the build runs. */
    private static final Map<String, Integer> REQUEST_SITES = Map.of(
            "PlannerCompile.java", 1, // shared: the one compile-main body, build + forecast
            "PlannerFixtures.java", 1, // shared: compile-test-fixtures, build + forecast
            "TestSupport.java", 1, // keyed: compile-test build
            "TaskForecaster.java", 1, // keyed: compile-test forecast
            "LocalProjectBuilder.java", 1, // unkeyed: source-dependency build calls JavacRunner directly
            "ScriptPlans.java", 1); // unkeyed: jk run <script> calls JavacRunner directly

    private static final String BAG = "List<String> tokens = List.of(";

    private static Path root;
    private static Map<String, Path> byName;

    @BeforeAll
    static void scan() throws IOException {
        root = RepoRoot.find(ForecastKeyParityTest.class);
        List<Path> sources = SourceText.javaUnder(root.resolve("server/engine/src/main/java"));
        assertThat(sources.size())
                .as("engine main sources; measured against 368")
                .isGreaterThanOrEqualTo(330);
        byName = new HashMap<>();
        for (Path p : sources) byName.merge(p.getFileName().toString(), p, (a, b) -> a);
    }

    /** Comments blanked so a javadoc naming a marker is not mistaken for the code that uses it. */
    private static String read(String file) throws IOException {
        Path p = byName.get(file);
        assertThat(p).as("no %s under server/engine/src/main/java", file).isNotNull();
        return SourceText.withoutComments(Files.readString(p));
    }

    // Java is not parsed here; these walk characters while honouring string and char literals,
    // which is all these shapes need and all a source tripwire should attempt.
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
        throw new AssertionError("unbalanced '" + open + "' at offset " + at);
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

    /** The {@code "prefix:"} literals opening each token of {@code siteId}'s bag. */
    private static TreeSet<String> tokenPrefixes(String siteId) throws IOException {
        String file = siteId.substring(0, siteId.indexOf('|'));
        String keyVar = siteId.substring(siteId.indexOf('|') + 1);
        String src = read(file);
        int callAt = src.indexOf("String " + keyVar + " = ActionKey.forArtifact(");
        assertThat(callAt)
                .as("declared site %s no longer exists; update the table", siteId)
                .isNotNegative();
        int bagAt = src.lastIndexOf(BAG, callAt);
        assertThat(bagAt)
                .as(
                        "%s no longer keys a `%s…)` bag — a bag this cannot read is a bag it cannot"
                                + " compare; restore the shape or move the pair onto a shared owner",
                        siteId, BAG)
                .isNotNegative();
        String between = src.substring(bagAt, callAt);
        assertThat(between)
                .as(
                        "%s's token bag is amended between its List.of and its key, so the literal list"
                                + " is not the whole fact set",
                        siteId)
                .doesNotContain("tokens.add")
                .doesNotContain("ActionKey.forArtifact(");

        Pattern prefix = Pattern.compile("^\"([A-Za-z][A-Za-z0-9_.-]*):");
        TreeSet<String> out = new TreeSet<>();
        for (String raw : splitTopLevel(balancedFrom(src, '(', ')', bagAt + BAG.length() - 1))) {
            String element = raw.strip();
            Matcher m = prefix.matcher(element);
            assertThat(m.find())
                    .as(
                            "%s token does not open with a \"prefix:\" literal, so no side can be"
                                    + " compared against it: %s",
                            siteId, element)
                    .isTrue();
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void every_for_artifact_site_is_declared() throws IOException {
        Pattern site = Pattern.compile("(?:String\\s+)?(\\w+)\\s*=\\s*ActionKey\\.forArtifact\\(");
        TreeSet<String> found = new TreeSet<>();
        for (Path f : SourceText.javaUnder(root.resolve("server/engine/src/main/java"))) {
            Matcher m = site.matcher(Files.readString(f));
            while (m.find()) found.add(f.getFileName() + "|" + m.group(1));
        }
        TreeSet<String> declared = new TreeSet<>();
        for (String[] p : PAIRS) {
            declared.add(p[1]);
            declared.add(p[2]);
        }
        declared.addAll(SHARED.keySet());
        declared.addAll(UNPAIRED.keySet());

        assertThat(found)
                .as("every ActionKey.forArtifact site must be declared — as half of a build/forecast"
                        + " pair, as a shared owner both sides call, or as unpaired with the reason"
                        + " there is no twin. An undeclared site is a key nobody has decided how to"
                        + " forecast")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void a_paired_key_hashes_the_same_prefixes_on_both_sides() throws IOException {
        for (String[] p : PAIRS) {
            assertThat(tokenPrefixes(p[1]))
                    .as(
                            "%s: the build and the forecast hash different facts, so `jk explain` reports"
                                    + " a phantom rebuild (or worse, misses a real one)",
                            p[0])
                    .isEqualTo(tokenPrefixes(p[2]));
        }
    }

    @Test
    void a_shared_key_owner_really_has_both_callers() throws IOException {
        for (var e : SHARED.entrySet()) {
            List<String> spec = e.getValue();
            assertThat(spec.size() - 1)
                    .as(
                            "%s (%s) is declared shared: the build AND the forecast go through one body",
                            spec.get(0), e.getKey())
                    .isGreaterThanOrEqualTo(2);
            for (String reach : spec.subList(1, spec.size())) {
                assertReaches(reach, spec.get(0) + " (" + e.getKey() + ")", "key");
            }
        }
    }

    /**
     * Reach points are literal, so a caller that forks the derivation instead of calling it either
     * shows up here (the literal is gone) or lands as a new undeclared site.
     */
    private static void assertReaches(String reach, String label, String kind) throws IOException {
        String file = reach.substring(0, reach.indexOf('|'));
        String literal = reach.substring(reach.indexOf('|') + 1);
        assertThat(read(file))
                .as(
                        "%s is declared a shared %s owner, but %s no longer reaches it (`%s` is gone)."
                                + " A shared owner with one caller is a second body waiting to drift",
                        label, kind, file, literal)
                .contains(literal);
    }

    @Test
    void an_unpaired_key_is_honest_about_whether_the_forecast_steps_it() throws IOException {
        String forecaster = read("TaskForecaster.java");
        TreeSet<String> stepArgs = new TreeSet<>();
        for (Pattern p : List.of(
                Pattern.compile("new TaskForecast\\.Task\\(\\s*([^,]+),"),
                Pattern.compile("\\bcompileStep\\(\\s*([^,]+),"))) {
            Matcher m = p.matcher(forecaster);
            while (m.find()) stepArgs.add(m.group(1).strip().replaceAll("\\s+", " "));
        }
        List<String> unresolved = stepArgs.stream()
                .filter(a -> !a.startsWith("\"") && !a.startsWith("TaskNames.") && !STEP_INDIRECTIONS.contains(a))
                .toList();
        assertThat(unresolved)
                .as("TaskForecaster names a step through something this scan cannot resolve. A step"
                        + " the scan cannot see is a step an unpaired key can hide behind — give it a"
                        + " literal or a TaskNames constant, or declare the indirection")
                .isEmpty();

        Map<String, String> taskNames = new HashMap<>();
        Matcher c = Pattern.compile("String\\s+([A-Z][A-Z_0-9]*)\\s*=\\s*\"([^\"]+)\"")
                .matcher(Files.readString(RepoRoot.file(
                        ForecastKeyParityTest.class, "shared/jk-api/src/main/java/cc/jumpkick/run/TaskNames.java")));
        while (c.find()) taskNames.put(c.group(1), c.group(2));

        TreeSet<String> steps = new TreeSet<>();
        for (String arg : stepArgs) {
            if (arg.startsWith("\"")) {
                steps.add(arg.replace("\"", ""));
            } else if (arg.startsWith("TaskNames.")) {
                String resolved = taskNames.get(arg.substring("TaskNames.".length()));
                assertThat(resolved).as("TaskNames has no constant %s", arg).isNotNull();
                steps.add(resolved);
            }
        }

        for (var e : UNPAIRED.entrySet()) {
            String taskName = (String) e.getValue()[0];
            boolean claims = (boolean) e.getValue()[1];
            assertThat(steps.contains(taskName))
                    .as(
                            "%s claims the forecast %s emit a `%s` step. If it does, explain shows that"
                                    + " step priced against no key of its own, or against another step's:"
                                    + " pair the site or move it onto a shared owner. Stated reason: %s",
                            e.getKey(), claims ? "does" : "does not", taskName, e.getValue()[2])
                    .isEqualTo(claims);
        }
    }

    @Test
    void a_keyed_compile_request_sets_the_same_javac_fields_on_both_sides() throws IOException {
        String actionKey = read("ActionKey.java");
        int at = actionKey.indexOf("public static String forJavac(");
        assertThat(at)
                .as("ActionKey.forJavac is gone; this has nothing to key off")
                .isNotNegative();
        String body = balancedFrom(actionKey, '{', '}', actionKey.indexOf('{', at));
        TreeSet<String> keyed = new TreeSet<>();
        Matcher m = Pattern.compile("request\\.([a-zA-Z][A-Za-z0-9]*)\\(\\)").matcher(body);
        while (m.find()) keyed.add(m.group(1));
        assertThat(keyed)
                .as("forJavac reads no CompileRequest field; the scan has rotted")
                .isNotEmpty();

        Map<String, Integer> found = new HashMap<>();
        for (Path f : SourceText.javaUnder(root.resolve("server/engine/src/main/java"))) {
            int n = 0;
            Matcher b = Pattern.compile("CompileRequest\\.builder\\(\\)").matcher(Files.readString(f));
            while (b.find()) n++;
            if (n > 0) found.put(f.getFileName().toString(), n);
        }
        assertThat(found)
                .as("every CompileRequest.builder() chain must be declared, so a new one is classified"
                        + " as keyed (it reaches ActionKey.forJavac and needs a forecast twin) or"
                        + " unkeyed before it can drift")
                .isEqualTo(REQUEST_SITES);

        for (String[] p : REQUEST_PAIRS) {
            TreeSet<String> build = new TreeSet<>(keyedSetters(p[1]));
            TreeSet<String> forecast = new TreeSet<>(keyedSetters(p[2]));
            build.retainAll(keyed);
            forecast.retainAll(keyed);
            assertThat(build)
                    .as(
                            "%s: the build's CompileRequest and the forecast's set different fields that"
                                    + " ActionKey.forJavac hashes (%s), so the two keys cannot match",
                            p[0], keyed)
                    .isEqualTo(forecast);
        }
    }

    @Test
    void a_shared_compile_request_owner_really_has_both_callers() throws IOException {
        for (var e : REQUEST_SHARED.entrySet()) {
            String ownerFile = e.getKey().substring(0, e.getKey().indexOf('|'));
            String ownerMarker = e.getKey().substring(e.getKey().indexOf('|') + 1);
            List<String> spec = e.getValue();
            assertThat(read(ownerFile))
                    .as("%s's shared CompileRequest owner is gone from %s", spec.get(0), ownerFile)
                    .contains(ownerMarker);
            assertThat(spec.size() - 1)
                    .as("%s is declared shared: build AND forecast derive the request from one body", spec.get(0))
                    .isGreaterThanOrEqualTo(2);
            for (String reach : spec.subList(1, spec.size())) {
                assertReaches(reach, spec.get(0) + " (" + e.getKey() + ")", "CompileRequest");
            }
        }
    }

    /** Every setter on the builder chain nearest {@code siteId}'s marker, followed to its build(). */
    private static TreeSet<String> keyedSetters(String siteId) throws IOException {
        String file = siteId.substring(0, siteId.indexOf('|'));
        String marker = siteId.substring(siteId.indexOf('|') + 1);
        String src = read(file);
        int markAt = src.indexOf(marker);
        assertThat(markAt >= 0 && src.indexOf(marker, markAt + 1) < 0)
                .as("`%s` must occur exactly once in %s to address a CompileRequest chain", marker, file)
                .isTrue();

        List<Integer> builders = new ArrayList<>();
        Matcher b = Pattern.compile("CompileRequest\\.builder\\(\\)").matcher(src);
        while (b.find()) builders.add(b.start());
        assertThat(builders).as("no CompileRequest.builder() in %s", file).isNotEmpty();
        int start = builders.stream()
                .min((x, y) -> Integer.compare(Math.abs(x - markAt), Math.abs(y - markAt)))
                .orElseThrow();

        TreeSet<String> setters = new TreeSet<>();
        walkChain(src, start, setters);
        // The fluent chain ends at the first `;`, but a builder held in a local keeps taking setters
        // afterwards — both keyed build sites add the Scala fields in a following `if`, and stopping
        // at the `;` is why an earlier version could not see the forecast setting none of them.
        if (!setters.contains("build")) {
            Matcher assign = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*$")
                    .matcher(src.substring(Math.max(0, start - 200), start));
            assertThat(assign.find())
                    .as(
                            "the CompileRequest chain in %s is not terminated by .build() and is not"
                                    + " assigned to a variable, so this cannot tell where it ends",
                            file)
                    .isTrue();
            String var = assign.group(1);
            String rest = src.substring(start);
            Matcher buildAt = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*\\.\\s*build\\s*\\(")
                    .matcher(rest);
            assertThat(buildAt.find())
                    .as("`%s` in %s never reaches .build(); the continuation scan has no end", var, file)
                    .isTrue();
            String tail = rest.substring(0, buildAt.start());
            Matcher cont =
                    Pattern.compile("\\b" + Pattern.quote(var) + "\\s*\\.").matcher(tail);
            while (cont.find()) walkChain(tail, cont.end() - 1, setters);
        }
        setters.remove("builder");
        setters.remove("build");
        return setters;
    }

    /** Collect top-level {@code .name(} setters from {@code from} until the statement's {@code ;}. */
    private static void walkChain(String s, int from, TreeSet<String> into) {
        Pattern setter = Pattern.compile("^\\.([a-zA-Z][A-Za-z0-9]*)\\s*\\(");
        int depth = 0;
        boolean inStr = false;
        boolean inChar = false;
        boolean esc = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
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
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ';' && depth == 0) {
                return;
            } else if (c == '.' && depth == 0) {
                Matcher m = setter.matcher(s.substring(i, Math.min(s.length(), i + 64)));
                if (m.find()) into.add(m.group(1));
            }
        }
    }
}
