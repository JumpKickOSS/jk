// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.house;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Fixture;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Skipped;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.guard.api.runtime.GuardRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.TimeUnit;
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
            // No Gradle build to compare against: every module manifest is examined for a script
            // twin and none has one, so there is nothing to reconcile and no site. The population
            // is the manifests looked at — zero would read as a rule that examined nothing.
            long manifests = 0;
            for (String module : model.modules()) {
                if (!module.isEmpty() && exists(text, module + "/jk.toml")) manifests++;
            }
            v.population(manifests);
            return;
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
    /** The one place the release CI bootstraps jk from is written; every workflow step reads it. */
    private static final String BOOTSTRAP_PIN = ".jk/ci-bootstrap-version";
    /** The branch gate's Gradle job: advisory by design, the one job {@code continue-on-error} is allowed on. */
    private static final String GRADLE_PARITY_JOB = "gradle-parity";

    private static final Pattern RELEASE_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern LITERAL_BOOTSTRAP = Pattern.compile("JK_VERSION=[\"']?\\d");
    private static final Pattern TREE_VERSION = Pattern.compile("(?m)^version\\s*=\\s*\"(\\d+\\.\\d+\\.\\d+)");

    @Guard(
            id = "ci-cadence",
            why =
                    "nightly CI runs the benches, coverage and the product smoke; the branch gate keeps the self-host job that is the only evidence jk still builds jk, bootstrapped from the hosted release "
                            + BOOTSTRAP_PIN + " pins and judged by the checkout's own jk",
            instead =
                    "restore the job, step, script or pin the detail names — a workflow that stops running a gate leaves the claim in the docs with nothing behind it")
    @Fixture("server/guard/fixtures/ci-cadence")
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
        Map<String, String> jobs = jobs(branch);
        String selfHost = jobs.get("self-host");
        if (selfHost == null)
            problems.add(
                    CI
                            + " must keep the self-host job — a pull request has to prove jk still builds and tests this checkout");
        else {
            if (!selfHost.contains("JK_HOME:"))
                problems.add(
                        "the self-host job must run against an isolated JK_HOME, or it can pass on state the pull request did not produce");
            for (String verb : List.of("jk build", "jk install", "jk guard", "jk test"))
                if (!selfHost.contains(verb)) problems.add(CI + "'s self-host job must run `" + verb + "`");
            if (selfHost.contains("gradlew"))
                problems.add(CI + "'s self-host job must not invoke gradlew: it bootstraps from the hosted release "
                        + BOOTSTRAP_PIN
                        + " pins, and a Gradle step there puts the bootstrap oracle back in the merge gate");
            if (!selfHost.contains("install.sh") || !selfHost.contains(BOOTSTRAP_PIN))
                problems.add(CI + "'s self-host job must bootstrap with install.sh at the version " + BOOTSTRAP_PIN
                        + " names, never a version spelled in the workflow");
        }
        for (Map.Entry<String, String> job : jobs.entrySet())
            if (job.getValue().contains("continue-on-error") && !job.getKey().equals(GRADLE_PARITY_JOB))
                problems.add(CI + "'s `" + job.getKey()
                        + "` job must not carry continue-on-error — the flag makes a merge requirement advisory without"
                        + " deleting the job; only `" + GRADLE_PARITY_JOB
                        + "` is advisory by design (docs/contributors/self-host.md)");
        String pin = textOrNull(text, BOOTSTRAP_PIN);
        Matcher treeVersion = TREE_VERSION.matcher(text(text, "jk.toml"));
        if (pin == null)
            problems.add(
                    BOOTSTRAP_PIN
                            + " is missing — the release CI bootstraps from is written there once and read by every workflow step");
        else if (!RELEASE_VERSION.matcher(pin.strip()).matches())
            problems.add(BOOTSTRAP_PIN + " must hold exactly one release version (x.y.z), not '" + pin.strip() + "'");
        else if (treeVersion.find() && newer(pin.strip(), treeVersion.group(1)))
            problems.add(BOOTSTRAP_PIN + " names " + pin.strip() + ", newer than this tree's own "
                    + treeVersion.group(1) + " — a bootstrap release is cut from a tree, so it is never ahead of one");
        for (String wf : text.files(".github/workflows/*.yml"))
            if (LITERAL_BOOTSTRAP.matcher(HouseRules.owner(text, wf)).find())
                problems.add(wf + " spells a JK_VERSION literal; the bootstrap version is read from " + BOOTSTRAP_PIN
                        + ", so a release bumps one file");
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
                    new TextSite(
                            p.startsWith(".github") || p.startsWith("build.gradle") || p.startsWith(".jk/")
                                    ? p.split("['\\s]")[0]
                                    : CI,
                            0,
                            p),
                    p);
        v.population(4);
    }

    /**
     * A workflow's jobs by id, each as the text of its block: the keys at indent two under {@code jobs:}, a block
     * running to the next such key or the next top-level key. Comment-blanked text keeps the line structure.
     */
    private static Map<String, String> jobs(String workflow) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> lines = workflow.lines().toList();
        int i = 0;
        while (i < lines.size() && !lines.get(i).startsWith("jobs:")) i++;
        String id = null;
        StringBuilder block = new StringBuilder();
        for (i++; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            int indent = indentOf(line);
            if (indent == 0) break;
            if (indent == 2 && line.strip().endsWith(":")) {
                if (id != null) out.put(id, block.toString());
                id = line.strip();
                id = id.substring(0, id.length() - 1);
                block.setLength(0);
                continue;
            }
            if (id != null) block.append(line).append('\n');
        }
        if (id != null) out.put(id, block.toString());
        return out;
    }

    /** True when release version {@code a} is newer than {@code b}, both {@code x.y.z}. */
    private static boolean newer(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int d = Integer.parseInt(as[i]) - Integer.parseInt(bs[i]);
            if (d != 0) return d > 0;
        }
        return false;
    }

    // ---- G99 ---------------------------------------------------------------------------------

    private static final Pattern PIPE_TO_TEE = Pattern.compile("\\|\\s*tee\\b");
    private static final Pattern SETS_PIPEFAIL = Pattern.compile("\\bset\\b[^\\n]*-[a-zA-Z]*o\\s+pipefail");
    /** A bare {@code bash} is GitHub's {@code bash --noprofile --norc -eo pipefail {0}}; a custom command must say pipefail itself. */
    private static final Pattern BASH_SHELL = Pattern.compile("^bash$|pipefail");

    @Guard(
            id = "workflow-tee-pipefail",
            why =
                    "a `run:` step without a `shell:` runs `bash -e {0}` with pipefail off, so `cmd | tee` exits with tee's status and a red command goes green",
            instead =
                    "`shell: bash` on the step or under the workflow's or job's `defaults.run`, or `set -o pipefail` at the top of the script")
    @Fixture("server/guard/fixtures/workflow-tee-pipefail")
    void workflowTeePipefail(Text text, Violations v) {
        List<String> workflows = text.files(".github/workflows/*.yml");
        for (String wf : workflows) {
            List<String> lines = text.lines(wf);
            String workflowShell = defaultShell(lines, 0, 0, lines.size());
            String jobShell = workflowShell;
            int jobIndent = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int indent = indentOf(line);
                if (line.isBlank() || line.strip().startsWith("#")) continue;
                if (line.startsWith("jobs:")) {
                    jobIndent = -2;
                    continue;
                }
                if (jobIndent == -2
                        && indent > 0
                        && line.strip().endsWith(":")
                        && !line.strip().startsWith("- ")) {
                    jobIndent = indent;
                }
                if (jobIndent >= 0 && indent == jobIndent && line.strip().endsWith(":")) {
                    int end = i + 1;
                    while (end < lines.size() && (lines.get(end).isBlank() || indentOf(lines.get(end)) > jobIndent))
                        end++;
                    String own = defaultShell(lines, jobIndent + 2, i + 1, end);
                    jobShell = own != null ? own : workflowShell;
                    continue;
                }
                if (!line.strip().startsWith("- ")) continue;
                int stepEnd = i + 1;
                while (stepEnd < lines.size()
                        && (lines.get(stepEnd).isBlank() || indentOf(lines.get(stepEnd)) > indent)) stepEnd++;
                List<String> step = lines.subList(i, stepEnd);
                String script = runScript(step, indent);
                if (script == null) continue;
                Matcher tee = PIPE_TO_TEE.matcher(script);
                if (!tee.find()) continue;
                String shell = keyValue(step, indent + 2, "shell");
                boolean covered = shell != null
                        ? BASH_SHELL.matcher(shell).find()
                        : (jobShell != null && BASH_SHELL.matcher(jobShell).find())
                                || SETS_PIPEFAIL.matcher(script).find();
                if (!covered)
                    v.add(
                            new TextSite(wf, i + 1, tee.group()),
                            wf
                                    + ": a run: step pipes into tee without pipefail — tee's exit status becomes the step's");
            }
        }
        v.population(workflows.size());
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    /** The {@code shell:} under a {@code defaults: run:} block at {@code indent} within {@code [from, to)}, or null. */
    private static @Nullable String defaultShell(List<String> lines, int indent, int from, int to) {
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            if (indentOf(line) != indent || !line.strip().equals("defaults:")) continue;
            int end = i + 1;
            while (end < to && (lines.get(end).isBlank() || indentOf(lines.get(end)) > indent)) end++;
            for (int j = i + 1; j < end; j++) {
                if (!lines.get(j).strip().equals("run:")) continue;
                int runIndent = indentOf(lines.get(j));
                int runEnd = j + 1;
                while (runEnd < end && (lines.get(runEnd).isBlank() || indentOf(lines.get(runEnd)) > runIndent))
                    runEnd++;
                return keyValue(lines.subList(j + 1, runEnd), runIndent + 2, "shell");
            }
        }
        return null;
    }

    /** The scalar value of {@code key:} at exactly {@code indent} in {@code lines}, unquoted, or null. */
    private static @Nullable String keyValue(List<String> lines, int indent, String key) {
        for (String line : lines) {
            if (indentOf(line) != indent) continue;
            String body = line.strip();
            if (!body.startsWith(key + ":")) continue;
            String value = body.substring(key.length() + 1).strip();
            if (value.length() >= 2 && (value.startsWith("'") || value.startsWith("\"")))
                value = value.substring(1, value.length() - 1);
            return value;
        }
        return null;
    }

    /**
     * The script of a step's {@code run:} — a block scalar's lines or its inline value — or null when the step has
     * none. The step's keys sit at {@code indent + 2}, aligned with the text after its {@code "- "}.
     */
    private static @Nullable String runScript(List<String> step, int indent) {
        for (int i = 0; i < step.size(); i++) {
            String line = step.get(i);
            String body = i == 0 ? line.strip().substring(2).strip() : line.strip();
            int at = i == 0 ? indent + 2 : indentOf(line);
            if (at != indent + 2 || !body.startsWith("run:")) continue;
            String value = body.substring(4).strip();
            if (!value.isEmpty() && !value.startsWith("|") && !value.startsWith(">")) return value;
            StringBuilder script = new StringBuilder();
            for (int j = i + 1; j < step.size(); j++) {
                String l = step.get(j);
                if (!l.isBlank() && indentOf(l) <= indent + 2) break;
                script.append(l.strip()).append('\n');
            }
            return script.toString();
        }
        return null;
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
        if (!HouseRules.owner(text, CI).contains("./gradlew curatedIntegrationTest"))
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

    private static final String SHELLCHECK = "scripts/shellcheck.sh";

    /** One finding in shellcheck's gcc format: {@code file:line:col: level: message [SCnnnn]}. */
    private static final Pattern SHELLCHECK_FINDING =
            Pattern.compile("^([^\\s:][^:]*):(\\d+):\\d+: (?:note|warning|error|style): (.*) \\[(SC\\d+)\\]$");

    /** The script count every message of the lint script carries. */
    private static final Pattern SCRIPT_COUNT = Pattern.compile("(\\d+) scripts");

    @Guard(
            id = "shellcheck",
            why =
                    "the installers, scripts/ and the wrapper template are run by users and CI, and an unquoted `$var` in a `[ ]` test (SC2086) is the defect shell scripts ship most",
            instead =
                    "quote the expansion; a finding that is intentional is silenced at its site with `# shellcheck disable=SCnnnn` and a reason")
    void shippedShellScriptsAreShellcheckClean(Text text, Violations v) throws IOException, InterruptedException {
        if (text.files("scripts/*.sh").isEmpty())
            throw new IllegalStateException("the tree has no scripts/*.sh; the scan is blind");
        GuardRuntime runtime = GuardRuntime.current();
        if (runtime == null) throw new IllegalStateException("no guard runtime: jk did not configure this JVM");
        Path root = runtime.root();
        if (!Files.isRegularFile(root.resolve(SHELLCHECK)))
            throw new IllegalStateException(SHELLCHECK + " is missing; the lint has no owner");
        // The script owns the target list and the runner choice; this guard runs it and reads what it says.
        Process process = new ProcessBuilder("bash", SHELLCHECK, "--format=gcc")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException(SHELLCHECK + " did not finish within 10 minutes");
        }
        List<String> lines = output.lines().toList();
        List<String> said =
                lines.stream().filter(l -> l.startsWith("shellcheck:")).toList();
        for (String line : said) {
            Matcher count = SCRIPT_COUNT.matcher(line);
            if (count.find()) v.population(Integer.parseInt(count.group(1)));
        }
        int status = process.exitValue();
        if (status == 0) {
            if (said.stream().noneMatch(l -> l.endsWith("scripts clean"))) throw new Skipped(String.join(" ", said));
            return;
        }
        int findings = 0;
        for (String line : lines) {
            Matcher m = SHELLCHECK_FINDING.matcher(line);
            if (!m.matches()) continue;
            findings++;
            v.add(
                    new TextSite(m.group(1), Integer.parseInt(m.group(2)), m.group(4) + " " + m.group(3)),
                    m.group(4) + ": " + m.group(3));
        }
        // a non-zero exit with no finding is the lint refusing to run (CI without the tool): red, at its owner
        if (findings == 0)
            v.add(
                    new TextSite(SHELLCHECK, 1, "exit " + status),
                    "the lint did not run (exit " + status + "): " + String.join(" ", said.isEmpty() ? lines : said));
    }
}
