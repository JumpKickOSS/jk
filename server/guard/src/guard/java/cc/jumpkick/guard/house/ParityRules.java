// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The parity and generated letters whose two sides the closed extractor vocabulary cannot both
 * read: Gradle scripts, Kotlin tables, workflow YAML, a registry with five fields, docs whose
 * expected text is a rendering. Each reads exactly what the gate script did and reports the site;
 * the engine owns the baseline, the {@code code} and the rendering.
 */
@GuardSuite(scope = Scope.WORKSPACE)
final class ParityRules {

    private static final String SETTINGS = "settings.gradle.kts";
    private static final Pattern PROJECT_DIR =
            Pattern.compile("project\\(\"(:[\\w-]+)\"\\)\\.projectDir\\s*=\\s*file\\(\"([^\"]+)\"\\)");

    private static String text(Text text, String path) {
        return String.join("\n", text.lines(path));
    }

    private static @Nullable String textOrNull(Text text, String path) {
        try {
            return text(text, path);
        } catch (RuntimeException absent) {
            return null;
        }
    }

    /** A direct read, not a walk: {@code Text.files} scans the tree, and an entry-by-entry check would scan it once per entry. */
    private static boolean exists(Text text, String path) {
        return textOrNull(text, path) != null;
    }

    /** The marker line (1-based) of {@code <name>:start}, or 0. */
    private static int markerLine(List<String> lines, String name) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(name + ":start")) return i + 1;
        return 0;
    }

    /** The text between the markers inclusive, as the gate compared it, or {@code null}. */
    private static @Nullable String block(List<String> lines, String name) {
        int s = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (s < 0 && lines.get(i).contains("<!-- " + name + ":start -->")) s = i;
            else if (s >= 0 && lines.get(i).contains("<!-- " + name + ":end -->"))
                return String.join("\n", lines.subList(s, i + 1));
        }
        return null;
    }

    // ---- G36 ---------------------------------------------------------------------------------

    private static final Set<String> TEST_CONFS = Set.of(
            "testImplementation",
            "testApi",
            "testRuntimeOnly",
            "testCompileOnly",
            "testFixturesApi",
            "testFixturesImplementation",
            "integrationTestImplementation");
    private static final Set<String> MAIN_CONFS =
            Set.of("implementation", "api", "compileOnly", "runtimeOnly", "annotationProcessor", "compileOnlyApi");
    private static final Pattern PROJECT_DEP =
            Pattern.compile("(\\w+)\\(\\s*(testFixtures\\(\\s*)?project\\(\"(:[\\w-]+)\"\\)");
    private static final Pattern MANIFEST_NAME = Pattern.compile("(?m)^name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern DOTTED_WS = Pattern.compile("^([\\w-]+)\\.workspace\\s*=\\s*true");
    private static final Pattern INLINE_DEP = Pattern.compile("^([\\w-]+)\\s*=\\s*\\{(.*)}");

    @Guard(
            id = "manifest-dep-parity",
            why =
                    "the repo builds itself with Gradle and with jk, so a workspace edge declared to one build is green there and broken in the other",
            instead =
                    "declare the edge in both build.gradle.kts and jk.toml — jk's `fixtures = true` is Gradle's `testFixtures(...)`, [test-dependencies] is testImplementation; fix whichever manifest is wrong, never delete the other declaration")
    void manifestDepParity(Model model, Text text, Violations v) {
        String settings = textOrNull(text, SETTINGS);
        if (settings == null) {
            v.population(0);
            return; // the Gradle build is gone; nothing to reconcile
        }
        Map<String, String> dirOf = new LinkedHashMap<>();
        Matcher pd = PROJECT_DIR.matcher(settings);
        while (pd.find()) dirOf.put(pd.group(1), pd.group(2));
        Map<String, String> nameOf = new LinkedHashMap<>();
        for (var e : dirOf.entrySet()) {
            String manifest = textOrNull(text, e.getValue() + "/jk.toml");
            if (manifest == null) continue;
            Matcher n = MANIFEST_NAME.matcher(manifest);
            if (n.find()) nameOf.put(e.getKey(), n.group(1));
        }
        if (nameOf.size() < 20)
            throw new IllegalStateException("mapped only " + nameOf.size()
                    + " project paths to artifact names, so this guard has lost settings.gradle.kts or the manifests");
        long compared = 0;
        for (String module : model.modules()) {
            if (module.isEmpty()) continue;
            String script = module + "/build.gradle.kts";
            String manifest = module + "/jk.toml";
            if (!exists(text, script) || !exists(text, manifest)) continue;
            compared++;
            Set<String> gradleMain = new TreeSet<>();
            Set<String> gradleTest = new TreeSet<>();
            Set<String> fixtures = new TreeSet<>();
            int edges = 0;
            String scriptText = text.blanked(script, Blank.COMMENTS);
            for (String line : scriptText.split("\n")) {
                Matcher m = PROJECT_DEP.matcher(line);
                while (m.find()) {
                    String name = nameOf.get(m.group(3));
                    if (name == null) continue;
                    edges++;
                    boolean isFixture = m.group(2) != null;
                    if (TEST_CONFS.contains(m.group(1))) {
                        gradleTest.add(name);
                        if (isFixture) fixtures.add(name);
                    } else if (MAIN_CONFS.contains(m.group(1))) gradleMain.add(name);
                }
            }
            if (scriptText.contains("project(\":") && edges == 0) {
                v.add(
                        new TextSite(script, 0, "scan broke"),
                        "found no project dependency in " + script + " although the text contains one; fix the scan");
                continue;
            }
            Set<String> jkMain = new TreeSet<>();
            Set<String> jkTest = new TreeSet<>();
            Set<String> jkFixtures = new TreeSet<>();
            String table = "";
            for (String raw : text.lines(manifest)) {
                int hash = raw.indexOf('#');
                String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
                if (line.startsWith("[")) {
                    table = line.replace("[", "").replace("]", "");
                    continue;
                }
                if (!table.endsWith("dependencies")) continue;
                Matcher dotted = DOTTED_WS.matcher(line);
                Matcher inline = INLINE_DEP.matcher(line);
                String name = null;
                boolean takesFixtures = false;
                if (dotted.find()) name = dotted.group(1);
                else if (inline.find()
                        && inline.group(2).contains("workspace")
                        && inline.group(2).contains("true")) {
                    name = inline.group(1);
                    takesFixtures = inline.group(2).matches(".*fixtures\\s*=\\s*true.*");
                }
                if (name == null) continue;
                if (table.startsWith("test-")) {
                    jkTest.add(name);
                    if (takesFixtures) jkFixtures.add(name);
                } else jkMain.add(name);
            }
            for (String d : minus(gradleMain, jkMain))
                fault(
                        v,
                        manifest,
                        module,
                        "Gradle declares " + d + " for the main tier; jk.toml [dependencies] does not");
            for (String d : minus(jkMain, gradleMain))
                fault(v, manifest, module, "jk.toml [dependencies] declares " + d + "; build.gradle.kts does not");
            Set<String> gradleTestOnly = minus(gradleTest, fixtures);
            gradleTestOnly.removeAll(jkTest);
            gradleTestOnly.removeAll(jkMain);
            for (String d : gradleTestOnly)
                fault(
                        v,
                        manifest,
                        module,
                        "Gradle declares " + d + " for the test tier; jk.toml [test-dependencies] does not");
            Set<String> jkTestOnly = minus(jkTest, gradleTest);
            jkTestOnly.removeAll(gradleMain);
            for (String d : jkTestOnly)
                fault(v, manifest, module, "jk.toml [test-dependencies] declares " + d + "; build.gradle.kts does not");
            for (String d : minus(fixtures, jkFixtures))
                fault(
                        v,
                        manifest,
                        module,
                        "Gradle takes " + d + "'s testFixtures; jk.toml needs `" + d
                                + " = { workspace = true, fixtures = true }` under a [test-*dependencies] table");
            for (String d : minus(jkFixtures, fixtures))
                fault(
                        v,
                        manifest,
                        module,
                        "jk.toml takes " + d
                                + " with fixtures = true; build.gradle.kts does not take its testFixtures");
        }
        v.population(compared);
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static void fault(Violations v, String file, String module, String what) {
        v.add(new TextSite(file, 0, what), module + " declares different dependencies to its two builds: " + what);
    }

    // ---- G52 ---------------------------------------------------------------------------------

    private static final String TEST_TIERS = "buildSrc/src/main/kotlin/TestTiers.kt";
    private static final String TIERS_DOC = "docs/contributors/test-suite-tiers.md";
    private static final Pattern TIER = Pattern.compile(
            "TestTier\\(\\s*(\\w+),\\s*include\\s*=\\s*(emptySet\\(\\)|setOf\\([^)]*\\)),\\s*exclude\\s*=\\s*(emptySet\\(\\)|setOf\\([^)]*\\)|slowTags\\.toSet\\(\\))\\s*,?\\s*\\)");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Guard(
            id = "test-tier-docs",
            why = "the documented tier table is read off TestTiers, never retyped; the list drifted by hand",
            instead =
                    "replace the marked table in docs/contributors/test-suite-tiers.md with the rendering in the detail")
    void testTierDocs(Text text, Violations v) {
        String model = HouseRules.owner(text, TEST_TIERS);
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher c = Pattern.compile("const val (\\w+) = \"([^\"]+)\"").matcher(model);
        while (c.find()) constants.put(c.group(1), c.group(2));
        Matcher st = Pattern.compile("val slowTags = listOf\\(([^)]*)\\)").matcher(model);
        if (!st.find()) throw new IllegalStateException("cannot read TestTiers.slowTags");
        Set<String> slowTags = quoted(st.group(1));
        List<String[]> tiers = new ArrayList<>();
        List<Set<String>> includes = new ArrayList<>();
        List<Set<String>> excludes = new ArrayList<>();
        Matcher t = TIER.matcher(model);
        while (t.find()) {
            String task = constants.get(t.group(1));
            if (task == null) throw new IllegalStateException("cannot resolve tier constant " + t.group(1));
            tiers.add(new String[] {task});
            includes.add(t.group(2).equals("slowTags.toSet()") ? slowTags : quoted(t.group(2)));
            excludes.add(t.group(3).equals("slowTags.toSet()") ? slowTags : quoted(t.group(3)));
        }
        if (tiers.size() != 5)
            throw new IllegalStateException("read " + tiers.size() + " tiers from TestTiers.all; expected 5");
        Matcher g = Pattern.compile("val gating = setOf\\(([^)]*)\\)").matcher(model);
        if (!g.find()) throw new IllegalStateException("cannot read TestTiers.gating");
        Set<String> gating = new LinkedHashSet<>();
        for (String n : g.group(1).split(",")) {
            String name = n.strip();
            if (name.isEmpty()) continue;
            String task = constants.get(name);
            if (task == null) throw new IllegalStateException("cannot resolve gating constant " + name);
            gating.add(task);
        }
        StringBuilder expected = new StringBuilder("<!-- test-tiers:start -->\n");
        expected.append("| Command | Includes | Excludes | In `checkAll`? |\n");
        expected.append("|---------|----------|----------|----------------|\n");
        for (int i = 0; i < tiers.size(); i++) {
            String task = tiers.get(i)[0];
            expected.append("| `./gradlew ")
                    .append(task)
                    .append("` | ")
                    .append(tags(includes.get(i), "untagged"))
                    .append(" | ")
                    .append(tags(excludes.get(i), "—"))
                    .append(" | ")
                    .append(gating.contains(task) ? "yes |" : "no |")
                    .append('\n');
        }
        expected.append("<!-- test-tiers:end -->");
        List<String> doc = text.lines(TIERS_DOC);
        String actual = block(doc, "test-tiers");
        if (actual == null) throw new IllegalStateException(TIERS_DOC + " is missing its generated tier table markers");
        if (!actual.equals(expected.toString()))
            v.add(
                    new TextSite(TIERS_DOC, markerLine(doc, "test-tiers"), "test-tiers table"),
                    TIERS_DOC + " differs from TestTiers; replace its marked table with:\n" + expected);
        v.population(tiers.size());
    }

    private static Set<String> quoted(String expression) {
        Set<String> out = new LinkedHashSet<>();
        Matcher q = QUOTED.matcher(expression);
        while (q.find()) out.add(q.group(1));
        return out;
    }

    private static String tags(Set<String> items, String fallback) {
        if (items.isEmpty()) return fallback;
        List<String> ticked = new ArrayList<>();
        for (String i : items) ticked.add("`" + i + "`");
        return String.join(", ", ticked);
    }

    // ---- G55 ---------------------------------------------------------------------------------

    private static final String ENGINE_CONTROLS = "shared/core/src/main/java/cc/jumpkick/config/EngineControls.java";
    private static final String ENGINE_DOC = "docs/user/engine.md";
    private static final Pattern CONTROL = Pattern.compile(
            "control\\(\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\"\\s*,\\s*(?:\"([^\"]*)\"|([A-Z_]+))\\s*,\\s*\"([^\"]*)\"\\s*\\)");

    @Guard(
            id = "engine-config-docs",
            why =
                    "docs/user/engine.md's two tables are read off EngineControls, never retyped; a knob documented from memory is a knob the engine does not read",
            instead = "replace the drifted table in docs/user/engine.md with the rendering in the detail")
    void engineConfigDocs(Text text, Violations v) {
        String model = HouseRules.owner(text, ENGINE_CONTROLS);
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher c =
                Pattern.compile("static final String ([A-Z_]+) = \"([^\"]*)\";").matcher(model);
        while (c.find()) constants.put(c.group(1), c.group(2));
        List<String[]> table = new ArrayList<>();
        List<String[]> process = new ArrayList<>();
        Matcher r = CONTROL.matcher(model);
        while (r.find()) {
            String read = r.group(4);
            if (read == null || read.isEmpty()) {
                read = constants.get(r.group(5));
                if (read == null) throw new IllegalStateException("EngineControls: unknown constant " + r.group(5));
            }
            String[] row = {r.group(1), r.group(2), r.group(3), read, r.group(6)};
            (row[0].isEmpty() ? process : table).add(row);
        }
        // the parser arm is the population floor: a regex that stops matching control() reads nothing
        if (table.size() < 5)
            throw new IllegalStateException(
                    "EngineControls TABLE parsed " + table.size() + " rows; expected at least 5");
        if (process.size() < 6)
            throw new IllegalStateException(
                    "EngineControls PROCESS parsed " + process.size() + " rows; expected at least 6");
        StringBuilder t = new StringBuilder(
                "<!-- engine-config:start -->\n| Key | Env | Default | Read | Meaning |\n|---|---|---|---|---|\n");
        for (String[] row : table)
            t.append("| `")
                    .append(row[0])
                    .append("` | `")
                    .append(row[1])
                    .append("` | ")
                    .append(row[2])
                    .append(" | ")
                    .append(row[3])
                    .append(" | ")
                    .append(row[4])
                    .append(" |\n");
        t.append("<!-- engine-config:end -->");
        StringBuilder p =
                new StringBuilder("<!-- engine-process:start -->\n| Env | Default | Meaning |\n|---|---|---|\n");
        for (String[] row : process)
            p.append("| `")
                    .append(row[1])
                    .append("` | ")
                    .append(row[2])
                    .append(" | ")
                    .append(row[4])
                    .append(" |\n");
        p.append("<!-- engine-process:end -->");
        List<String> doc = text.lines(ENGINE_DOC);
        for (var pair :
                List.of(new String[] {"engine-config", t.toString()}, new String[] {"engine-process", p.toString()})) {
            String present = block(doc, pair[0]);
            if (present == null)
                throw new IllegalStateException(ENGINE_DOC + " is missing its " + pair[0] + " table markers");
            if (!present.equals(pair[1]))
                v.add(
                        new TextSite(ENGINE_DOC, markerLine(doc, pair[0]), pair[0] + " table"),
                        ENGINE_DOC + " differs from EngineControls; replace the " + pair[0] + " table with:\n"
                                + pair[1]);
        }
        v.population(table.size() + process.size());
    }

    // ---- G56 (Node arm) ----------------------------------------------------------------------

    @Guard(
            id = "node-pin-parity",
            why =
                    "the dashboard's Node version is pinned once, in .nvmrc; a workflow that pins its own runs the JS gate on a Node nobody chose",
            instead =
                    "keep .nvmrc a bare version token and give every actions/setup-node step `node-version-file: '.nvmrc'` and no `node-version`")
    void nodePinParity(Text text, Violations v) {
        String pin = textOrNull(text, ".nvmrc");
        pin = pin == null ? "" : pin.strip();
        if (!pin.matches("\\d+(\\.\\d+)*"))
            v.add(
                    new TextSite(".nvmrc", 1, "pin"),
                    ".nvmrc must be a Node version token (got " + (pin.isEmpty() ? "missing" : pin) + ")");
        List<String> workflows = text.files(".github/workflows/*.yml");
        long setupNode = 0;
        for (String wf : workflows) {
            String body = text(text, wf);
            if (!body.contains("actions/setup-node")) continue;
            setupNode++;
            if (!Pattern.compile("node-version-file:\\s*['\"]\\.nvmrc['\"]")
                    .matcher(body)
                    .find())
                v.add(
                        new TextSite(wf, 0, "node-version-file"),
                        wf + ": setup-node must set node-version-file: '.nvmrc'");
            if (Pattern.compile("(?m)^\\s+node-version:\\s").matcher(body).find())
                v.add(
                        new TextSite(wf, 0, "node-version"),
                        wf + ": setup-node must not also set node-version (the pin is .nvmrc)");
        }
        if (setupNode == 0)
            v.add(
                    new TextSite(".github/workflows", 0, "no setup-node"),
                    "no workflow uses actions/setup-node — the dashboard JS gate would skip Node");
        v.population(workflows.size() + 1);
    }

    // ---- G57 ---------------------------------------------------------------------------------

    private static final String CI = ".github/workflows/ci.yml";
    private static final String NIGHTLY = ".github/workflows/ci-nightly.yml";
    private static final String WALL = ".github/workflows/wall-measure.yml";

    @Guard(
            id = "ci-cadence",
            why =
                    "nightly CI runs the benches, coverage and the product smoke; the branch gate keeps the self-host job that is the only evidence jk still builds jk",
            instead =
                    "restore the job, step or script the detail names — a workflow that stops running a gate leaves the claim in the docs with nothing behind it")
    void ciCadence(Text text, Violations v) {
        String nightly = HouseRules.owner(text, NIGHTLY);
        String branch = HouseRules.owner(text, CI);
        List<String> problems = new ArrayList<>();
        if (!exists(text, "scripts/ci-product-smoke.sh")) problems.add("scripts/ci-product-smoke.sh is missing");
        if (!nightly.contains("./gradlew benchTest")) problems.add(NIGHTLY + " must run ./gradlew benchTest");
        if (!nightly.contains("coverageReport") || !nightly.contains("-Pjk.coverage"))
            problems.add(NIGHTLY + " must run coverageReport -Pjk.coverage");
        if (!nightly.contains("jk test --coverage"))
            problems.add(
                    NIGHTLY + " must run jk test --coverage before its jk guard step — the coverage ratchet (G91)");
        if (!nightly.contains("macos-")) problems.add(NIGHTLY + " must have a macOS smoke runner");
        if (!nightly.contains("windows-")) problems.add(NIGHTLY + " must have a Windows smoke runner");
        if (!nightly.contains("ci-product-smoke.sh")) problems.add(NIGHTLY + " must run scripts/ci-product-smoke.sh");
        if (branch.contains("coverageReport") || branch.contains("-Pjk.coverage") || branch.contains("benchTest"))
            problems.add(CI + " must not run coverage or benches (they are nightly, non-gating)");
        if (!text(text, "build.gradle.kts").contains("\"coverageReport\""))
            problems.add("build.gradle.kts must register coverageReport");
        if (!branch.contains("self-host:"))
            problems.add(
                    CI
                            + " must keep the self-host job — a pull request has to prove jk still builds and tests this checkout");
        if (!branch.contains("JK_HOME:"))
            problems.add(
                    "the self-host job must run against an isolated JK_HOME, or it can pass on state the pull request did not produce");
        for (String verb : List.of("jk build", "jk test"))
            if (!branch.contains(verb)) problems.add(CI + "'s self-host job must run `" + verb + "`");
        if (branch.contains("continue-on-error"))
            problems.add(
                    CI + " must not carry continue-on-error — the self-host job is a merge requirement, and a flag that"
                            + " makes it advisory retires the oracle without deleting the job (docs/contributors/self-host.md)");
        if (!exists(text, "scripts/dogfood-wall-measure.sh"))
            problems.add("scripts/dogfood-wall-measure.sh is missing");
        String wall = textOrNull(text, WALL);
        if (wall == null) {
            problems.add(
                    WALL
                            + " is missing — the Gradle/jk wall comparison is scheduled, not something a contributor has to remember");
        } else {
            for (String must : List.of("schedule:", "dogfood-wall-measure.sh", "upload-artifact", "row.jsonl"))
                if (!wall.contains(must))
                    problems.add(WALL + " must keep '" + must + "': a measurement nobody schedules, or whose"
                            + " machine-readable result nobody keeps, is not a baseline");
        }
        for (String p : problems)
            v.add(
                    new TextSite(p.startsWith(".github") || p.startsWith("build.gradle") ? p.split(" ")[0] : CI, 0, p),
                    p);
        v.population(3);
    }

    // ---- G58 ---------------------------------------------------------------------------------

    @Guard(
            id = "security-docs",
            why =
                    "a reporter who cannot find the advisory URL reports in public; SECURITY.md, docs/user/security.md and the user README point at it and at each other",
            instead = "restore the file or the link the detail names")
    void securityDocs(Text text, Violations v) {
        String advisory = "security/advisories";
        String policy = textOrNull(text, "SECURITY.md");
        if (policy == null) v.add(new TextSite("SECURITY.md", 0, "missing"), "SECURITY.md is missing");
        else {
            if (!policy.contains("docs/user/security.md"))
                v.add(
                        new TextSite("SECURITY.md", 0, "no page link"),
                        "SECURITY.md must point at docs/user/security.md");
            if (!policy.contains(advisory))
                v.add(
                        new TextSite("SECURITY.md", 0, "no advisory url"),
                        "SECURITY.md must name the GitHub security/advisories URL");
        }
        String page = textOrNull(text, "docs/user/security.md");
        if (page == null)
            v.add(new TextSite("docs/user/security.md", 0, "missing"), "docs/user/security.md is missing");
        else if (!page.contains(advisory))
            v.add(
                    new TextSite("docs/user/security.md", 0, "no advisory url"),
                    "docs/user/security.md must name the GitHub security/advisories URL");
        if (!text(text, "docs/user/README.md").contains("](security.md)"))
            v.add(
                    new TextSite("docs/user/README.md", 0, "no security link"),
                    "docs/user/README.md must link security.md");
        v.population(3);
    }

    // ---- G63 ---------------------------------------------------------------------------------

    private static final String REGISTRY = "curated-integration.txt";
    private static final Map<String, String> SURFACES = new LinkedHashMap<>();
    private static final Map<String, String> CURATED_MODULES =
            Map.of(":cli", "clients/cli", ":engine", "server/engine");
    private static final Set<String> OUTCOMES = Set.of("success", "failure");
    private static final List<String> FAILURE_ASSERTIONS = List.of(
            "assertThatThrownBy",
            "assertThrows",
            "catchThrowable",
            "assertThatExceptionOfType",
            "isNotEqualTo(0)",
            "isNotZero");
    private static final List<String> REFUSAL_WORDS = List.of(
            "fail",
            "refus",
            "reject",
            "denie",
            "invalid",
            "missing",
            "unknown",
            "error",
            "stale",
            "mismatch",
            "conflict",
            "nonzero",
            "orphan",
            "ghost",
            "corrupt",
            "crash",
            "timeout",
            "noop",
            "no_op",
            "not_",
            "never",
            "without",
            "bad_",
            "loses");
    private static final List<String> DISQUALIFYING = List.of("slow", "network", "bench");
    private static final Pattern METHOD_NAME = Pattern.compile("\\bvoid\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    static {
        SURFACES.put("wire", "CLI to engine JSONL wire");
        SURFACES.put("spawn", "engine spawn, election and takeover");
        SURFACES.put("workers", "worker and plugin process launch");
        SURFACES.put("workspace", "workspace build and test");
        SURFACES.put("install", "install and materialize");
        SURFACES.put("lock", "lockfile and action cache");
    }

    private record Entry(String module, String fqcn, String surface, Set<String> paths, int line) {}

    @Guard(
            id = "curated-integration",
            why =
                    "the curated integration lane is the only integration coverage a pull request gets; an entry that no longer runs, lost its tag, or claims a path it does not show is a boundary nobody is watching until the nightly build",
            instead =
                    "fix the registry entry, the class, or the workflow the detail names — never reclassify a class to make the gate pass")
    void curatedIntegration(Text text, Violations v) {
        List<String> lines = text.lines(REGISTRY);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String body = lines.get(i).strip();
            if (body.isEmpty() || body.startsWith("#")) continue;
            int n = i + 1;
            String[] fields = body.split("\\|");
            for (int k = 0; k < fields.length; k++) fields[k] = fields[k].strip();
            if (fields.length != 5) {
                v.add(
                        new TextSite(REGISTRY, n, body),
                        REGISTRY + ":" + n + ": " + fields.length
                                + " fields, expected 5 (module | class | surface | outcomes | why)");
                continue;
            }
            Set<String> paths = new TreeSet<>();
            for (String p : fields[3].split(",")) paths.add(p.strip());
            String fault = null;
            if (!CURATED_MODULES.containsKey(fields[0]))
                fault = "unknown module '" + fields[0] + "'; one of " + CURATED_MODULES.keySet();
            else if (!fields[1].contains(".")) fault = "'" + fields[1] + "' is not a fully-qualified class name";
            else if (!SURFACES.containsKey(fields[2]))
                fault = "unknown surface '" + fields[2] + "'; one of " + SURFACES.keySet();
            else if (!OUTCOMES.containsAll(paths))
                fault = "unknown outcome(s) " + minus(paths, OUTCOMES) + "; one or both of " + OUTCOMES;
            else if (fields[4].length() < 20)
                fault = "the reason is " + fields[4].length()
                        + " characters; say what merges broken without this class";
            if (fault != null) v.add(new TextSite(REGISTRY, n, body), REGISTRY + ":" + n + ": " + fault);
            else entries.add(new Entry(fields[0], fields[1], fields[2], paths, n));
        }
        if (entries.isEmpty())
            throw new IllegalStateException(
                    REGISTRY + " names no classes, so the curated lane would run nothing and report green");
        // Floor under the scan: read from the comment-blanked view, because a class whose javadoc
        // quotes @Tag("integration") to say why it is NOT tagged reads as tagged otherwise.
        long tagged = 0;
        for (String f : text.files("**/src/test/java/**/*.java"))
            if (text.blanked(f, Blank.COMMENTS).contains("@Tag(\"integration\")")) tagged++;
        if (tagged < 90)
            throw new IllegalStateException(
                    "found " + tagged + " @Tag(\"integration\") classes; measured against 109 and floored at 90 —"
                            + " either the tier shrank into the curated subset or the scan broke");
        Map<String, List<Entry>> byFqcn = new TreeMap<>();
        for (Entry e : entries)
            byFqcn.computeIfAbsent(e.fqcn(), k -> new ArrayList<>()).add(e);
        for (var e : byFqcn.entrySet())
            if (e.getValue().size() > 1)
                v.add(
                        new TextSite(REGISTRY, e.getValue().get(0).line(), e.getKey() + " duplicate"),
                        e.getKey() + " is listed " + e.getValue().size() + " times");
        for (Entry entry : entries) {
            String rel = CURATED_MODULES.get(entry.module()) + "/src/test/java/"
                    + entry.fqcn().replace('.', '/') + ".java";
            if (!exists(text, rel)) {
                v.add(
                        new TextSite(REGISTRY, entry.line(), entry.fqcn()),
                        entry.fqcn() + ": no source at " + rel
                                + " — the class was renamed, moved, or deleted; update the registry");
                continue;
            }
            String body = text.blanked(rel, Blank.COMMENTS);
            if (!body.contains("@Tag(\"integration\")"))
                v.add(
                        new TextSite(rel, 0, "untagged"),
                        entry.fqcn() + ": not @Tag(\"integration\"), so the lane's filters never select it");
            for (String d : DISQUALIFYING)
                if (body.contains("@Tag(\"" + d + "\")"))
                    v.add(
                            new TextSite(rel, 0, "@Tag " + d),
                            entry.fqcn() + ": carries @Tag(\"" + d
                                    + "\"), which is a nightly tier — the branch gate must not need the network, a framework toolchain, or a benchmark");
            if (entry.paths().contains("failure")) {
                boolean asserts = FAILURE_ASSERTIONS.stream().anyMatch(body::contains);
                boolean refuses = false;
                Matcher m = METHOD_NAME.matcher(body);
                while (m.find() && !refuses) {
                    String name = m.group(1).toLowerCase(Locale.ROOT);
                    for (String w : REFUSAL_WORDS) if (name.contains(w)) refuses = true;
                }
                if (!asserts && !refuses)
                    v.add(
                            new TextSite(rel, 0, "failure claim"),
                            entry.fqcn() + ": claims a failure path and shows none — no " + FAILURE_ASSERTIONS
                                    + " and no test method naming a refusal. Drop the claim, or register a class that has one");
            }
        }
        Set<String> modules = new TreeSet<>();
        for (Entry e : entries) modules.add(e.module());
        for (String module : modules) {
            String script = CURATED_MODULES.get(module) + "/build.gradle.kts";
            String body = textOrNull(text, script);
            if (body != null
                    && body.contains("integrationTest")
                    && !body.contains("CuratedIntegration.integrationTasks"))
                v.add(
                        new TextSite(script, 0, "integrationTest by name"),
                        module + " configures integrationTest by name; the registry"
                                + " names classes in it, so it must configure CuratedIntegration.integrationTasks instead and give the lane the tier's environment");
        }
        for (var s : SURFACES.entrySet()) {
            List<Entry> here =
                    entries.stream().filter(e -> e.surface().equals(s.getKey())).toList();
            if (here.isEmpty()) {
                v.add(
                        new TextSite(REGISTRY, 0, "surface " + s.getKey()),
                        "surface '" + s.getKey() + "' (" + s.getValue() + ") has no entry");
                continue;
            }
            List<String> gaps = new ArrayList<>();
            for (String path : new TreeSet<>(OUTCOMES))
                if (here.stream().noneMatch(e -> e.paths().contains(path))) gaps.add(path);
            if (!gaps.isEmpty())
                v.add(
                        new TextSite(REGISTRY, 0, "surface " + s.getKey() + " gaps"),
                        "surface '" + s.getKey() + "' (" + s.getValue() + ") has no " + String.join(" or ", gaps)
                                + " path");
        }
        if (!HouseRules.owner(text, CI).contains("curatedIntegrationTest"))
            v.add(
                    new TextSite(CI, 0, "curatedIntegrationTest"),
                    CI
                            + " must run ./gradlew curatedIntegrationTest — a registry no pull request executes is documentation, not a gate");
        if (!HouseRules.owner(text, NIGHTLY).contains("./gradlew integrationTest"))
            v.add(
                    new TextSite(NIGHTLY, 0, "integrationTest"),
                    NIGHTLY
                            + " must still run the full ./gradlew integrationTest; the curated lane is a subset, never a replacement");
        String doc = text(text, TIERS_DOC);
        if (!doc.contains(REGISTRY))
            v.add(new TextSite(TIERS_DOC, 0, "registry"), TIERS_DOC + " must name " + REGISTRY);
        if (!doc.contains("8 minutes"))
            v.add(
                    new TextSite(TIERS_DOC, 0, "budget"),
                    TIERS_DOC
                            + " must state the lane's budget as '8 minutes', the number buildSrc/src/main/kotlin/CuratedIntegration.kt owns");
        v.population(entries.size());
    }

    // ---- checkStageDocs' twin ----------------------------------------------------------------

    private static final String BUILD_STAGE = "shared/jk-api/src/main/java/cc/jumpkick/run/BuildStage.java";
    private static final Map<String, String> STAGE_DOCS = new LinkedHashMap<>();

    static {
        STAGE_DOCS.put("docs/user/machine-output.md", "list");
        STAGE_DOCS.put("docs/user/explain.md", "list");
        STAGE_DOCS.put("docs/contributors/architecture.md", "arrow");
    }

    @Guard(
            id = "stage-docs",
            why =
                    "the published stage taxonomy is read off BuildStage, never retyped; machine-output.md is a contract consumers integrate against",
            instead = "update the doc's stage list to match the enum, in pipeline order")
    void stageDocs(Text text, Violations v) {
        String src = HouseRules.owner(text, BUILD_STAGE);
        List<String> wires = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^ {4}[A-Z_]+\\(\"([a-z]+)\"\\)").matcher(src);
        while (m.find()) wires.add(m.group(1));
        if (wires.size() < 8)
            throw new IllegalStateException("read " + wires.size() + " stages from BuildStage.java — the parse broke");
        List<String> ticked = new ArrayList<>();
        for (String w : wires) ticked.add("`" + w + "`");
        String asList = String.join(", ", ticked);
        String asArrows = String.join(" → ", wires);
        for (var d : STAGE_DOCS.entrySet()) {
            String body = text(text, d.getKey()).replaceAll("\\s+", " ");
            String expected = d.getValue().equals("list") ? asList : asArrows;
            if (!body.contains(expected))
                v.add(
                        new TextSite(d.getKey(), 0, "stage list"),
                        d.getKey() + " publishes a different stage list than BuildStage's " + wires.size()
                                + " wire values (" + asArrows + ")");
        }
        v.population(wires.size());
    }

    // ---- checkDocLinks' twin -----------------------------------------------------------------

    private static final Pattern LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)\\s]+)\\)");

    @Guard(
            id = "doc-links-resolve",
            why =
                    "the redirect stubs and the user/contributors split multiplied the ways a docs/ link can rot, and nothing caught one",
            instead = "fix the path or the moved file the detail names")
    void docLinksResolve(Text text, Violations v) {
        Set<String> all = new HashSet<>(text.files("**"));
        Set<String> dirs = new HashSet<>();
        for (String f : all) {
            int slash = f.lastIndexOf('/');
            while (slash > 0) {
                dirs.add(f.substring(0, slash));
                slash = f.lastIndexOf('/', slash - 1);
            }
        }
        long links = 0;
        for (String doc : text.files("docs/**/*.md")) {
            String prose = text(text, doc).replaceAll("(?s)```.*?```", "").replaceAll("`[^`\\n]*`", "");
            String dir = doc.contains("/") ? doc.substring(0, doc.lastIndexOf('/')) : "";
            Matcher m = LINK.matcher(prose);
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://")
                        || target.startsWith("https://")
                        || target.startsWith("mailto:")
                        || target.startsWith("#")) continue;
                String path = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                if (path.isEmpty()) continue;
                links++;
                String resolved = normalize(dir.isEmpty() ? path : dir + "/" + path);
                if (!all.contains(resolved) && !dirs.contains(resolved))
                    v.add(
                            new TextSite(doc, 0, target),
                            doc + ": " + target + " points at nothing — fix the path or the moved file");
            }
        }
        v.population(links);
    }

    private static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(seg);
        }
        return String.join("/", out);
    }

    // ---- checkGuardRegistry's twin -----------------------------------------------------------

    private static final String GUARDS_KT = "buildSrc/src/main/kotlin/Guards.kt";
    private static final String CHARTER = "docs/contributors/code-as-art.md";

    @Guard(
            id = "guard-letters-registry",
            why =
                    "the published guard registry lists exactly the letters the build enforces; it fell behind the code twice by hand",
            instead =
                    "regenerate the marked table (./gradlew checkGuardRegistry prints it) and keep every ruleId / guardTestId pointing at a rule that exists")
    void guardLettersRegistry(Text text, Violations v) {
        String kt = HouseRules.owner(text, GUARDS_KT);
        Set<String> letters = new TreeSet<>();
        Matcher s = Pattern.compile("spec\\(\\s*(\\d+),").matcher(kt);
        while (s.find()) letters.add("G" + s.group(1));
        if (letters.size() < 60)
            throw new IllegalStateException("read " + letters.size() + " letters from Guards.kt — the parse broke");
        Set<String> rows = new TreeSet<>();
        for (String line : text.lines(CHARTER)) {
            Matcher r = Pattern.compile("^\\| (G\\d+) \\|").matcher(line);
            if (r.find()) rows.add(r.group(1));
        }
        for (String g : minus(letters, rows))
            v.add(new TextSite(CHARTER, 0, g), g + " is in Guards.kt and not in the registry table");
        for (String g : minus(rows, letters))
            v.add(new TextSite(CHARTER, 0, g), g + " is in the registry table and not in Guards.kt");
        Set<String> ruleIds = new TreeSet<>();
        for (String line : text.lines("jk-guards.toml")) {
            Matcher r = Pattern.compile("^\\[guards\\.([a-z0-9-]+)]").matcher(line);
            if (r.find()) ruleIds.add(r.group(1));
        }
        Matcher rid = Pattern.compile("ruleId = \"([a-z0-9-]+)\"").matcher(kt);
        while (rid.find())
            if (!ruleIds.contains(rid.group(1)))
                v.add(
                        new TextSite(GUARDS_KT, 0, rid.group(1)),
                        "Guards.kt names ruleId `" + rid.group(1) + "` and jk-guards.toml has no such rule");
        Set<String> guardIds = new TreeSet<>();
        for (String f : text.files("**/src/guard/java/**/*.java")) {
            Matcher g = Pattern.compile("id = \"([a-z0-9-]+)\"").matcher(text.blanked(f, Blank.COMMENTS));
            while (g.find()) guardIds.add(g.group(1));
        }
        Matcher gid = Pattern.compile("guardTestId = \"([a-z0-9-]+)\"").matcher(kt);
        while (gid.find())
            if (!guardIds.contains(gid.group(1)))
                v.add(
                        new TextSite(GUARDS_KT, 0, gid.group(1)),
                        "Guards.kt names guardTestId `" + gid.group(1) + "` and no @Guard declares it");
        v.population(letters.size());
    }
    // ---- G51 -------------------------------------------------------------------------------------

    private static final String PARITY_EXCUSES = "guard-parity.txt";

    /**
     * One registry letter, merged over its entries (a letter may have one per side): whether any entry
     * is a Gradle task, and whether any names a jk side.
     */
    private record Letter(int n, boolean gradle, boolean jkSide) {}

    private static boolean gradleEntry(String block) {
        Matcher o = Pattern.compile("gradleLetter = (true|false)").matcher(block);
        if (o.find()) return Boolean.parseBoolean(o.group(1));
        Matcher h = Pattern.compile("GuardHome\\.(\\w+)").matcher(block);
        String home = h.find() ? h.group(1) : "";
        return !home.equals("SELF_HOSTED") && !home.equals("TEST") && !home.equals("FOLDED") && !home.equals("NEVER");
    }

    private static List<Letter> letters(String kt) {
        List<Integer> starts = new ArrayList<>();
        Matcher s = Pattern.compile("spec\\(\\s*(\\d+),").matcher(kt);
        while (s.find()) starts.add(s.start());
        int end = kt.indexOf("private fun spec(");
        if (end < 0) end = kt.length();
        Map<Integer, boolean[]> byLetter = new TreeMap<>();
        for (int i = 0; i < starts.size(); i++) {
            String block = kt.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : end);
            Matcher n = Pattern.compile("spec\\(\\s*(\\d+),").matcher(block);
            if (!n.find()) continue;
            boolean[] sides = byLetter.computeIfAbsent(Integer.parseInt(n.group(1)), k -> new boolean[2]);
            if (gradleEntry(block)) sides[0] = true;
            if (block.contains("ruleId = \"")
                    || block.contains("engineCode = \"")
                    || block.contains("guardTestId = \"")) sides[1] = true;
        }
        List<Letter> out = new ArrayList<>();
        for (var e : byLetter.entrySet()) out.add(new Letter(e.getKey(), e.getValue()[0], e.getValue()[1]));
        return out;
    }

    @Guard(
            id = "guard-parity",
            why =
                    "the repo builds itself twice, so a house rule one build enforces and the other does not is enforced half the time; the registry says which side each letter lives on",
            instead =
                    "give the letter a jk side in Guards.kt (ruleId, engineCode or guardTestId), or record in guard-parity.txt why it is Gradle-only; drop an entry once the letter has both")
    void guardParity(Text text, Violations v) {
        String kt = HouseRules.owner(text, GUARDS_KT);
        List<Letter> letters = letters(kt);
        if (letters.size() < 60)
            throw new IllegalStateException(
                    "read " + letters.size() + " registry entries from Guards.kt; the parse broke");
        Set<Integer> excused = new TreeSet<>();
        List<String> excuseLines = text.lines(PARITY_EXCUSES);
        for (String line : excuseLines) {
            Matcher m = Pattern.compile("^G(\\d+)\\s").matcher(line);
            if (m.find()) excused.add(Integer.parseInt(m.group(1)));
        }
        if (excused.isEmpty())
            throw new IllegalStateException(
                    PARITY_EXCUSES + " lists no letters, so this guard would pass over anything");
        Set<Integer> seen = new TreeSet<>();
        for (Letter l : letters) {
            seen.add(l.n());
            if (l.gradle() && !l.jkSide() && !excused.contains(l.n()))
                v.add(
                        new TextSite(GUARDS_KT, 0, "G" + l.n()),
                        "G" + l.n() + " is enforced by Gradle only: name its jk side, or excuse it in "
                                + PARITY_EXCUSES);
            if (l.jkSide() && excused.contains(l.n()))
                v.add(
                        new TextSite(PARITY_EXCUSES, 0, "G" + l.n()),
                        "G" + l.n()
                                + " is excused as Gradle-only but has a jk side; drop the entry, parity is real now");
        }
        for (int n : excused)
            if (!seen.contains(n))
                v.add(new TextSite(PARITY_EXCUSES, 0, "G" + n), "G" + n + " is excused but is not a registry letter");
        v.population(letters.size());
    }

    // ---- G72 -------------------------------------------------------------------------------------

    private static String slug(String heading) {
        StringBuilder sb = new StringBuilder();
        for (char c : heading.replace("`", "").toLowerCase(Locale.ROOT).toCharArray())
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-') sb.append(c);
        return sb.toString().strip().replace(' ', '-');
    }

    @Guard(
            id = "charter-parity",
            why =
                    "a cap the charter states and the build does not enforce is worse than no cap, because a reader trusts the table; the Contents list is a fact about the file",
            instead =
                    "make the Size table agree with [guards.file-size].cap in jk-guards.toml, and the Contents list with the headings, in the same commit")
    void charterParity(Text text, Violations v) {
        List<String> lines = text.lines(CHARTER);
        Pattern row = Pattern.compile("^\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|");
        Map<String, @Nullable Integer> docCaps = new LinkedHashMap<>();
        Map<String, Integer> docLine = new LinkedHashMap<>();
        boolean inTable = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("| Language | Extensions |")) {
                inTable = true;
                continue;
            }
            if (inTable && !line.startsWith("|")) inTable = false;
            if (!inTable || line.startsWith("|---")) continue;
            Matcher m = row.matcher(line);
            if (!m.find()) continue;
            String hardCell = m.group(4).strip();
            Integer hard = hardCell.equals("—") ? null : parseIntOrNull(hardCell.replace(",", ""));
            Matcher ext = Pattern.compile("`\\.([a-z]+)`").matcher(m.group(2));
            while (ext.find()) {
                docCaps.put(ext.group(1), hard);
                docLine.put(ext.group(1), i + 1);
            }
        }
        if (docCaps.isEmpty())
            v.add(
                    new TextSite(CHARTER, 0, "Size table"),
                    "the Size table was not found; it needs a `| Language | Extensions | Soft | Hard | Exception |` header and one backticked extension per language");
        String rules = text(text, "jk-guards.toml");
        Matcher cap = Pattern.compile(
                        "\\[guards\\.file-size]\\n(?:(?!\\n\\[guards\\.).)*?\\ncap\\s*=\\s*\\{([^}]*)}", Pattern.DOTALL)
                .matcher(rules);
        if (!cap.find())
            throw new IllegalStateException(
                    "jk-guards.toml no longer declares [guards.file-size] with a cap table, so this guard has lost the caps it compares");
        Map<String, Integer> enforced = new TreeMap<>();
        Matcher e = Pattern.compile("(\\w+)\\s*=\\s*(\\d+)").matcher(cap.group(1));
        while (e.find()) enforced.put(e.group(1), Integer.parseInt(e.group(2)));
        if (enforced.isEmpty())
            throw new IllegalStateException("[guards.file-size].cap names no language, so this guard compares nothing");
        Set<String> exts = new TreeSet<>(docCaps.keySet());
        exts.addAll(enforced.keySet());
        for (String ext : exts) {
            String doc = docCaps.containsKey(ext)
                    ? String.valueOf(docCaps.get(ext) == null ? "exempt" : docCaps.get(ext))
                    : "absent";
            String rule = enforced.containsKey(ext) ? String.valueOf(enforced.get(ext)) : "exempt";
            if (!doc.equals(rule))
                v.add(
                        new TextSite(CHARTER, docLine.getOrDefault(ext, 0), "." + ext),
                        "." + ext + ": the charter says " + doc + ", [guards.file-size] enforces " + rule);
        }
        List<String> toc = new ArrayList<>();
        int tocLine = 0;
        List<String> headings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher t = Pattern.compile("^ *- \\[(.+)]\\(#([a-z0-9-]+)\\)$")
                    .matcher(lines.get(i).stripTrailing());
            if (t.find()) {
                toc.add(t.group(2));
                if (tocLine == 0) tocLine = i + 1;
            }
            Matcher h = Pattern.compile("^(##|###) (.+)$").matcher(lines.get(i));
            if (h.find() && !h.group(2).equals("Contents")) headings.add(slug(h.group(2)));
        }
        boolean drift = false;
        for (String h : headings)
            if (!toc.contains(h)) {
                drift = true;
                v.add(new TextSite(CHARTER, tocLine, "#" + h), "missing from Contents: #" + h);
            }
        for (String t : toc)
            if (!headings.contains(t)) {
                drift = true;
                v.add(new TextSite(CHARTER, tocLine, "#" + t), "in Contents, no such heading: #" + t);
            }
        if (!drift && !toc.equals(headings))
            v.add(new TextSite(CHARTER, tocLine, "Contents"), "Contents lists every heading but in a different order");
        v.population(exts.size() + headings.size());
    }

    private static @Nullable Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException nan) {
            return null;
        }
    }
}
