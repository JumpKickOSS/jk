// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.config.WorkspaceModules.expand;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.eval.CoverageReport;
import cc.jumpkick.guard.eval.EvalContext;
import cc.jumpkick.guard.eval.Evaluators;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.eval.LaneRun;
import cc.jumpkick.guard.eval.LintReport;
import cc.jumpkick.guard.eval.OutputArtifacts;
import cc.jumpkick.guard.eval.TreeCorpus;
import cc.jumpkick.guard.eval.WorkspaceModel;
import cc.jumpkick.guard.eval.WorkspaceModules;
import cc.jumpkick.guard.explain.RuleSummaries;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LocalInstallRows;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathAbi;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.KeyAudit;
import cc.jumpkick.task.KeyContract;
import cc.jumpkick.task.KeyOwner;
import cc.jumpkick.task.ToolchainPolicy;
import cc.jumpkick.test.StampTokens;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The one owner of a guard lane's action key, called by the lane ({@link PlannerGuards}) and by
 * the forecast ({@link TaskForecaster}). The forecast needs it because the dirty set is "a module
 * with a non-cached material step": without a {@code guard} step the forecast never enters a
 * module whose lane verdict is stale, and a rule edit changes nothing until something else does.
 * The forecast probes read-only — a stale facts index is a RUN, never an extraction.
 */
final class GuardKeys {

    private GuardKeys() {}

    /** The lane key: the lane's read-set tokens plus the baseline slice the verdict was judged against. */
    @KeyOwner(step = TaskNames.GUARD + "*")
    static String laneKey(String taskId, List<String> tokens, String baselineSlice) {
        List<String> all = new ArrayList<>(tokens);
        all.add("baseline:" + baselineSlice);
        String key = ActionKey.forArtifact(taskId, KeyContract.ARTIFACT.keyVersion(), all);
        return key;
    }

    /**
     * {@link #laneKey(String, List, String)} for a run of lane {@code lane} at {@code dir}; a module
     * lane's key names the engine JDK's major, so its reader's expectation is stated with it ({@link
     * ToolchainPolicy#expectOnEngineMajor}).
     */
    static String laneKey(Lane lane, String taskId, List<String> tokens, String baselineSlice, Path dir) {
        String key = laneKey(taskId, tokens, baselineSlice);
        if (lane == Lane.MODULE) ToolchainPolicy.expectOnEngineMajor(key, dir);
        return key;
    }

    /**
     * A digest of the part of the baseline lane {@code lane} at {@code module} ({@code ""} at the
     * root) judges against, so a lane tightening its own slice, or a freeze of another lane's rule,
     * leaves every other lane's verdict cached. A declared rule the lane owns contributes its {@link
     * LaneRun#sliceOf slice}; a rule of another lane contributes nothing. An id no TOML rule declares
     * is a guard test of some suite or an orphan: a module lane reads it in its own slice and the
     * root's, a root lane in the root's. The model lane also reads the ids themselves, since it
     * reports the orphans.
     */
    static String baselineSlice(Path root, Lane lane, String module, RuleSet rules) throws IOException {
        Path f = GuardsPresence.baselineFile(root);
        if (!Files.isRegularFile(f)) return "none";
        Baseline baseline = parsedBaseline(f);
        MessageDigest md = Hashing.newSha256();
        for (var e : new TreeMap<>(baseline.rules()).entrySet()) {
            String id = e.getKey();
            if (lane == Lane.MODEL) feed(md, "id", id);
            Rule rule = rules.rules().get(id);
            List<String> slices;
            if (rule == null) slices = lane == Lane.MODULE ? List.of(module, "") : List.of("");
            else if (Evaluators.laneOf(rule) == lane
                    || (lane == Lane.MODULE
                            && LaneRun.sliceOf(lane, rule, module).isEmpty()))
                slices = List.of(LaneRun.sliceOf(lane, rule, module));
            else continue;
            RuleBaseline rb = e.getValue();
            for (String slice : slices) {
                Map<String, Long> population = rb.population(slice);
                List<Entry> entries = rb.entries(slice);
                String reason = rb.scopeReason(slice);
                if (population.isEmpty() && entries.isEmpty() && reason == null) continue;
                feed(md, "rule", id + "@" + slice);
                feed(md, "population", new TreeMap<>(population).toString());
                feed(md, "reason", String.valueOf(reason));
                for (Entry entry : entries) feed(md, "entry", entry.toString());
            }
        }
        return Hashing.hex(md.digest());
    }

    /** {@link #baselineSlice} of the lane {@code ectx} runs, at its module. */
    static String slice(PlannerGuards.GuardsPlan g, Lane lane, EvalContext ectx, LoadResult load) throws IOException {
        return baselineSlice(g.root(), lane, ectx.module(), load.rules());
    }

    private static void feed(MessageDigest md, String tag, String value) {
        KeyAudit.tap(tag + "=" + value);
        md.update((tag + "\0" + value + "\0").getBytes(StandardCharsets.UTF_8));
    }

    /** The baseline as {@link BaselineFile#read} memoizes it: every lane of a build reads the same file. */
    private static Baseline parsedBaseline(Path file) throws IOException {
        return BaselineFile.read(file.toAbsolutePath().normalize());
    }

    static void addRuleTokens(List<String> tokens, LoadResult load) {
        for (var e : load.rules().sourceDigests().entrySet()) tokens.add("rules:" + e.getKey() + ":" + e.getValue());
    }

    /**
     * The forecast's {@code guard} step for one module, or empty when the workspace has no guards.
     * {@code testsIndexed} is whether the plan's lane indexes the test classes ({@link
     * PlannerGuards#indexesTestClasses}). CACHED only when the facts index is current and the lane's
     * verdict is in the cache; RUN otherwise, which is what makes the module dirty.
     */
    static Optional<TaskForecast.Task> forecastModuleLane(
            Path dir, BuildLayout layout, ActionCache actionCache, boolean upstreamDirty, boolean testsIndexed) {
        Path root = WorkspaceScan.findRoot(dir).orElse(dir).toAbsolutePath().normalize();
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!PlannerGuards.moduleLanesOnThisBuild(g, PlannerGuards.guardRequested())) return Optional.empty();
        // A unit with no classes directory has no module lane (a sourceless root, a never-built module).
        if (!Files.isDirectory(layout.classesDir()) && !upstreamDirty) return Optional.empty();
        if (upstreamDirty) return Optional.of(run("guards · module recompiles"));
        try {
            LoadResult load = PlannerGuards.rules(g);
            LaneProbe probe = probeModuleLane(root, dir, layout, load, testsIndexed);
            String key = probe.key();
            if (key == null) return Optional.of(run(probe.runReason()));
            String taskId = ActionKey.qualifiedTaskId(TaskNames.GUARD, dir);
            ToolchainPolicy.expectOnEngineMajor(key, dir);
            Optional<ActionCache.ActionRecord> verdict = GuardVerdicts.lookup(actionCache, key, root);
            if (verdict.isPresent()) {
                // A verdict that carries the lane's evidence is cached only where that evidence is
                // on disk: a fresh checkout runs the lane once so the hit restores it for the tree
                // lane's no-bite judgement.
                if (verdict.get().outputs().isEmpty() || Files.isRegularFile(RuleSummaries.file(root, taskId))) {
                    return Optional.of(new TaskForecast.Task(
                            TaskNames.GUARD, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
                }
                return Optional.of(run("guards · lane evidence to restore"));
            }
            return Optional.of(run(
                    missReason(actionCache, taskId, probe, load.rules().rules().size())));
        } catch (IOException e) {
            return Optional.of(run("guards · " + e.getMessage()));
        }
    }

    /** The module-lane key token of a run that compiled no tests, and so ran no rule over them. */
    private static final String TESTS_NOT_COMPILED = "tests:not-compiled";

    /**
     * A module lane's forecast key, with the tokens and baseline slice it was taken over, or why the
     * lane runs with none (an index to extract first).
     */
    record LaneProbe(@Nullable String key, String runReason, List<String> tokens, String slice) {

        LaneProbe(String runReason) {
            this(null, runReason, List.of(), "");
        }
    }

    /**
     * The key the module lane at {@code dir} would run under, read-only: its tokens come from
     * {@link #moduleLaneTokens}, as the run's do.
     */
    static LaneProbe probeModuleLane(Path root, Path dir, BuildLayout layout, LoadResult load, boolean testsIndexed)
            throws IOException {
        if (load.hasErrors()) return new LaneProbe("guards · jk-guards.toml does not load");
        Optional<String> main =
                FactsIndexing.freshDigest(layout.classesDir(), FactsIndexing.indexPath(layout.buildDir(), "main"));
        if (main.isEmpty()) return new LaneProbe("guards · facts index stale");
        Path testClasses = layout.testClassesDir();
        String testFacts = null;
        if (testsIndexed && Files.isDirectory(testClasses)) {
            Optional<String> test =
                    FactsIndexing.freshDigest(testClasses, FactsIndexing.indexPath(layout.buildDir(), "test"));
            if (test.isEmpty()) return new LaneProbe("guards · test facts index stale");
            testFacts = test.get();
        }
        List<String> suiteTokens = List.of();
        Path guardClasses = layout.guardClassesDir();
        if (Files.isDirectory(guardClasses) && PlannerGuardSuite.declared(dir, PlannerGuards.compact(dir))) {
            Path suiteIndex = FactsIndexing.indexPath(layout.buildDir(), "guard");
            Optional<String> suite = FactsIndexing.freshDigest(guardClasses, suiteIndex);
            if (suite.isEmpty()) return new LaneProbe("guards · guard suite index stale");
            List<GuardSuites.Declared> declared = GuardSuites.declared(FactsFormat.read(suiteIndex));
            suiteTokens = suiteTokens(suite.get(), declared, root, PlannerGuards.workspaceModuleDirs(root, null));
        }
        List<String> tokens =
                moduleLaneTokens(WorkspaceModel.rel(root, dir), main.get(), dir, testsIndexed, testFacts, suiteTokens);
        addRuleTokens(tokens, load);
        String slice = baselineSlice(root, Lane.MODULE, WorkspaceModel.rel(root, dir), load.rules());
        return new LaneProbe(
                laneKey(ActionKey.qualifiedTaskId(TaskNames.GUARD, dir), tokens, slice), "", tokens, slice);
    }

    /**
     * A module lane's tokens ahead of its rules', for the run and the forecast alike: {@link
     * #moduleTokens}, the test classes' facts digest when the lane indexes a test tree ({@code
     * testFacts}, null without one), a token saying the build compiled no tests when it did not, so
     * the lane's narrower verdict is not the build's, then the guard suite's {@link #suiteTokens}
     * ({@code suite}, empty without one).
     */
    static List<String> moduleLaneTokens(
            String module,
            String factsDigest,
            Path dir,
            boolean testsIndexed,
            @Nullable String testFacts,
            List<String> suite)
            throws IOException {
        List<String> tokens = moduleTokens(module, factsDigest, dir, testFacts != null);
        if (testFacts != null) tokens.add("test-facts:" + testFacts);
        if (!testsIndexed) tokens.add(TESTS_NOT_COMPILED);
        tokens.addAll(suite);
        return tokens;
    }

    /**
     * The head of a module lane's key: the module, its facts
     * digest, and what its type hierarchy resolves through besides its own classes — the Java level
     * of the JDK the engine runs on ({@link ToolchainPolicy#engineMajor}: a verdict is a record field
     * the reader matches by {@code jdk-match}, never the build), and the ABI of
     * each compile-scope sibling's classes tree, in closure order, then, when the lane reads a test
     * tree ({@code tests}), of each sibling only the tests compile against, where a test's tags may
     * be composed. An override or a subtype check moves with either, while the module's own facts do
     * not. The siblings are read off the manifest on disk, so the run and the forecast name the same
     * ones.
     */
    private static List<String> moduleTokens(String module, String factsDigest, Path dir, boolean tests)
            throws IOException {
        JkBuild project = JkBuildParser.parse(ManifestPaths.manifestIn(dir));
        List<String> tokens = new ArrayList<>();
        tokens.add("module:" + module);
        tokens.add("facts:" + factsDigest);
        tokens.add("java:" + ToolchainPolicy.engineMajor());
        WorkspaceClasspath.Result siblings =
                WorkspaceClasspath.resolve(dir, project, WorkspaceClasspath.COMPILE_SCOPES);
        for (Path classes : siblings.siblingClosureClasses()) tokens.add("sibling:" + ClasspathAbi.token(classes));
        if (tests) {
            for (Path classes : WorkspaceClasspath.resolve(dir, project, WorkspaceClasspath.TEST_SCOPES)
                    .siblingClosureClasses()) {
                if (!siblings.siblingClosureClasses().contains(classes))
                    tokens.add("test-sibling:" + ClasspathAbi.token(classes));
            }
        }
        return tokens;
    }

    /**
     * What a module's {@code src/guard} suite adds to its lane's key, for the run and the forecast
     * alike: the suite's index digest, every module's facts when a guard reads the workspace, the
     * tree's text when a guard reads {@code Text}, so an edit to root text re-runs that suite, and
     * every manifest and the lock when a guard reads {@code Model}. A suite is code that may branch
     * on the operating system ({@code Skipped.onThisPlatform}), so only a lane that runs one keys
     * the OS: the declarative rules read facts, text and manifests, the same bytes on every host.
     */
    static List<String> suiteTokens(
            String suiteDigest, List<GuardSuites.Declared> declared, Path root, List<Path> workspaceModules)
            throws IOException {
        List<String> tokens = new ArrayList<>();
        tokens.add("guard-suite:" + suiteDigest);
        tokens.add("os:" + Os.name());
        List<String> reads = new ArrayList<>();
        if (GuardSuites.anyWorkspace(declared)) {
            reads.addAll(workspaceTokens(root, workspaceModules));
            reads.addAll(workspaceTestTokens(root, workspaceModules));
        }
        if (GuardSuites.anyReadsText(declared)) reads.addAll(BuildLogicSupport.workspaceInputTokens(root));
        if (GuardSuites.anyReadsModel(declared)) {
            reads.add(fileToken("manifest", ManifestPaths.manifestIn(root)));
            for (Path m : workspaceModules) {
                reads.add(fileToken("manifest:" + root.relativize(m), ManifestPaths.manifestIn(m)));
            }
            reads.add(fileToken("lock", root.resolve(ManifestPaths.LOCK)));
        }
        if (!reads.isEmpty()) tokens.add(SUITE_READS + ":" + Hashing.sha256Hex(String.join("\n", reads)));
        return tokens;
    }

    /**
     * The token holding the digest of what a guard suite reads beyond its own module. One token, so
     * the verdict record keeps it whole and the forecast can tell a miss it alone caused.
     */
    static final String SUITE_READS = "suite-reads";

    /**
     * Why a module lane whose key missed runs. When the last verdict differs from {@code probe} only
     * in {@link #SUITE_READS}, the module's own inputs held and its guard suite's workspace input
     * moved, and the reason says so.
     */
    static String missReason(ActionCache actionCache, String taskId, LaneProbe probe, int rules) {
        String otherwise = rules + " rules to evaluate";
        if (actionCache.madeByAnotherJk(taskId)) return missed(actionCache, taskId, otherwise);
        String prefix = SUITE_READS + ":";
        int at = -1;
        for (int i = 0; i < probe.tokens().size(); i++)
            if (probe.tokens().get(i).startsWith(prefix)) at = i;
        if (at < 0) return "guards · " + otherwise;
        try {
            Optional<ActionCache.ActionRecord> last = actionCache.lastFor(taskId);
            String before = last.map(r -> r.inputs().get(SUITE_READS)).orElse(null);
            if (before == null || probe.tokens().get(at).equals(prefix + before)) return "guards · " + otherwise;
            List<String> then = new ArrayList<>(probe.tokens());
            then.set(at, prefix + before);
            if (laneKey(taskId, then, probe.slice()).equals(last.get().actionKey())) {
                return "guards · " + rules + " rules · src/guard reads the workspace and it changed";
            }
        } catch (IOException e) {
            Log.debug("guard forecast: the last verdict of " + taskId + " is unreadable", e);
        }
        return "guards · " + otherwise;
    }

    /**
     * Every module's main index digest, from the index headers alone: {@code facts:<module>:<digest>},
     * or {@code absent} for a module with no index yet (never built, or its compile failed).
     */
    static List<String> workspaceTokens(Path root, List<Path> modules) throws IOException {
        List<String> tokens = new ArrayList<>();
        for (Path m : modules) {
            Path buildDir = BuildLayout.moduleTargetDir(m);
            Path classes = buildDir.resolve("classes");
            Path idx = FactsIndexing.indexPath(buildDir, "main");
            String digest = Files.isDirectory(classes)
                    ? FactsIndexing.freshDigest(classes, idx).orElse("stale")
                    : "absent";
            tokens.add("facts:" + WorkspaceModel.rel(root, m) + ":" + digest);
        }
        return tokens;
    }

    /**
     * Every module's test index digest, which a workspace guard suite reads beside the main ones:
     * {@code test-facts:<module>:<digest>}, or {@code absent} for a module with no test classes.
     */
    static List<String> workspaceTestTokens(Path root, List<Path> modules) throws IOException {
        List<String> tokens = new ArrayList<>();
        for (Path m : modules) {
            Path buildDir = BuildLayout.moduleTargetDir(m);
            Path classes = buildDir.resolve("test-classes");
            Path idx = FactsIndexing.indexPath(buildDir, "test");
            String digest = Files.isDirectory(classes)
                    ? FactsIndexing.freshDigest(classes, idx).orElse("stale")
                    : "absent";
            tokens.add("test-facts:" + WorkspaceModel.rel(root, m) + ":" + digest);
        }
        return tokens;
    }

    /**
     * The workspace lane's tokens ahead of its rules', for its run and its forecast alike: every
     * module's main index digest ({@link #workspaceTokens}), then {@code removed:<module>:<classes>}
     * for each module holding classes of sources that are gone, which the lane reads as not there.
     * When {@code readsUnseen} ({@link #readsUnseenSources}), {@code unseen:<module>:<packages>} for
     * each module with main sources no index shows, which a split-package allow is judged against.
     */
    @KeyOwner(step = TaskNames.GUARD_WORKSPACE)
    static List<String> workspaceLaneTokens(Path root, List<Path> modules, boolean readsUnseen) throws IOException {
        List<String> tokens = workspaceTokens(root, modules);
        for (Path m : modules) {
            Set<String> removed = WorkspaceFacts.removedClasses(root, m);
            if (!removed.isEmpty())
                tokens.add("removed:" + WorkspaceModel.rel(root, m) + ":" + String.join(",", removed));
        }
        if (readsUnseen) {
            for (var e : WorkspaceFacts.unseenPackages(root, modules).entrySet())
                tokens.add("unseen:" + WorkspaceModel.rel(root, e.getKey()) + ":" + String.join(",", e.getValue()));
        }
        return tokens;
    }

    /** Whether a rule of {@code rules} reads sources no index shows: a split-package rule with an allow. */
    static boolean readsUnseenSources(RuleSet rules) {
        for (Rule r : rules.ofKind(Kind.SPLIT_PACKAGE)) if (!r.allow().isEmpty()) return true;
        return false;
    }

    /** The forecast's {@code guard-workspace} step at the workspace root, or empty when it has nothing to judge. */
    static Optional<TaskForecast.Task> forecastWorkspaceLane(Path root, ActionCache actionCache) {
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!PlannerGuards.moduleLanesOnThisBuild(g, PlannerGuards.guardRequested())) return Optional.empty();
        try {
            List<Path> modules = WorkspaceModules.of(root);
            if (modules.isEmpty() || modules.equals(List.of(root))) return Optional.empty();
            LoadResult load = PlannerGuards.rules(g);
            if (load.hasErrors()) return Optional.of(workspace("guards · jk-guards.toml does not load"));
            if (!LaneRun.judges(Lane.WORKSPACE, LaneRun.rulesFor(Lane.WORKSPACE, load.rules(), "")))
                return Optional.empty();
            List<String> tokens = workspaceLaneTokens(root, modules, readsUnseenSources(load.rules()));
            addRuleTokens(tokens, load);
            String key = laneKey(
                    ActionKey.qualifiedTaskId(TaskNames.GUARD_WORKSPACE, root),
                    tokens,
                    baselineSlice(root, Lane.WORKSPACE, "", load.rules()));
            if (GuardVerdicts.lookup(actionCache, key, root).isPresent()) {
                return Optional.of(new TaskForecast.Task(
                        TaskNames.GUARD_WORKSPACE, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
            }
            return Optional.of(workspace(missed(
                    actionCache,
                    ActionKey.qualifiedTaskId(TaskNames.GUARD_WORKSPACE, root),
                    "workspace rules to evaluate")));
        } catch (IOException e) {
            return Optional.of(workspace("guards · " + e.getMessage()));
        }
    }

    /**
     * The output lane's key material: each module's jar, POM and native binary by content, through
     * the stat-memoized hash — the sidecar POM is rewritten on every packaging pass and an
     * untouched jar is never re-read, so only a real change moves the key — its coverage report by
     * {@link #coverageStamp} and every lint report the {@code lint.*} measures may read by {@link
     * #lintStamp}. A file not on disk is {@code absent}.
     */
    static List<String> outputTokens(Path root, List<Path> modules, @Nullable String coverageReport)
            throws IOException {
        List<String> tokens = new ArrayList<>();
        for (OutputArtifacts.Module m : OutputArtifacts.of(root, modules, coverageReport)) {
            for (Path p : List.of(m.jar(), m.pom(), m.nativeBinary())) {
                String stamp = "absent";
                if (Files.isRegularFile(p)) {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    stamp = FileHashMemo.contentHash(p.toAbsolutePath().normalize(), a);
                }
                tokens.add("out:" + WorkspaceModel.rel(root, p) + ":" + stamp);
            }
            tokens.add("out:" + WorkspaceModel.rel(root, m.coverage()) + ":" + coverageStamp(m.coverage()));
            for (String tool : OutputArtifacts.LINT_TOOLS) {
                for (Path p : m.lintReports(tool)) {
                    tokens.add("out:" + WorkspaceModel.rel(root, p) + ":" + lintStamp(p));
                }
            }
        }
        return tokens;
    }

    /** A report's stamp by its path, stamped with the report's content hash: one parse per version. */
    private static final StampedMemo<Path, String, String> REPORT_STAMPS = StampedMemo.bounded(4_096);

    /** What a report reader makes of a report's bytes, or why it could not read them. */
    private interface ReportReader {
        String read(Path report) throws IOException;
    }

    /**
     * {@code reader}'s stamp of {@code report}, memoized by its content hash; {@code absent} when not
     * on disk, {@code unreadable} when it does not parse as a report (the lane reports that).
     */
    private static String reportStamp(Path report, ReportReader reader) throws IOException {
        if (!Files.isRegularFile(report)) return "absent";
        Path abs = report.toAbsolutePath().normalize();
        return REPORT_STAMPS.get(abs, FileHashMemo.contentHash(abs), () -> {
            try {
                return reader.read(abs);
            } catch (IOException | RuntimeException unparsed) {
                return "unreadable";
            }
        });
    }

    /**
     * A lint report as the {@code lint.*} measures read it: its finding count ({@link
     * LintReport#findings}), never its bytes, which carry the checkout's absolute paths and the
     * run's timestamp — so two checkouts with the same findings key alike. A measure that reads a
     * report per rule or per file must key that here too.
     */
    static String lintStamp(Path report) throws IOException {
        return reportStamp(report, r -> {
            Integer count = LintReport.findings(r);
            return count == null ? "unreadable" : "findings=" + count;
        });
    }

    /**
     * A JaCoCo report as the {@code coverage.*} measures read it: its whole-report counters ({@link
     * CoverageReport#counters}), never its bytes, which carry each run's session ids and times — so
     * a rerun measuring the same coverage, here or on another host, keys alike. A measure that reads
     * more of the report than these counters (per package, per class) must key that here too.
     */
    static String coverageStamp(Path report) throws IOException {
        return reportStamp(report, r -> "counters=" + CoverageReport.counters(r));
    }

    /** The forecast's {@code guard-output} step, or empty when no rule is on the output lane. */
    static Optional<TaskForecast.Task> forecastOutputLane(Path root, ActionCache actionCache) {
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!g.enabled()) return Optional.empty();
        try {
            LoadResult load = PlannerGuards.rules(g);
            if (load.hasErrors()) return Optional.empty();
            boolean any = false;
            for (Rule r : load.rules().rules().values()) if (Evaluators.laneOf(r) == Lane.OUTPUT) any = true;
            if (!any) return Optional.empty();
            List<String> tokens =
                    outputTokens(root, WorkspaceModules.of(root), g.config().coverageReport());
            addRuleTokens(tokens, load);
            String key = laneKey(
                    ActionKey.qualifiedTaskId(TaskNames.GUARD_OUTPUT, root),
                    tokens,
                    baselineSlice(root, Lane.OUTPUT, "", load.rules()));
            if (GuardVerdicts.lookup(actionCache, key, root).isPresent()) {
                return Optional.of(new TaskForecast.Task(
                        TaskNames.GUARD_OUTPUT, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
            }
            return Optional.of(new TaskForecast.Task(
                    TaskNames.GUARD_OUTPUT,
                    TaskForecast.Status.RUN,
                    missed(
                            actionCache,
                            ActionKey.qualifiedTaskId(TaskNames.GUARD_OUTPUT, root),
                            "output rules to evaluate"),
                    ""));
        } catch (IOException e) {
            return Optional.of(new TaskForecast.Task(
                    TaskNames.GUARD_OUTPUT, TaskForecast.Status.RUN, "guards · " + e.getMessage(), ""));
        }
    }

    /**
     * The tree lane's tokens ahead of its rules', for its run and its forecast alike: every file its
     * text rules read, by the rules' own walk, with its content hash.
     */
    @KeyOwner(step = TaskNames.GUARD_TREE)
    static List<String> treeLaneTokens(Path root) throws IOException {
        List<String> tokens = new ArrayList<>();
        TreeCorpus.walk(
                root, (rel, file, attrs) -> tokens.add("tree:" + rel + ":" + FileHashMemo.contentHash(file, attrs)));
        Collections.sort(tokens);
        return tokens;
    }

    /**
     * {@code guard-tree}, gate only: the text scan's verdict, keyed on the tree's inputs, rules and
     * baseline; empty when no rule is on the tree lane.
     */
    static Optional<TaskForecast.Task> forecastTreeLane(Path root, ActionCache actionCache) {
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!g.enabled() || !PlannerGuards.guardRequested()) return Optional.empty();
        try {
            LoadResult load = PlannerGuards.rules(g);
            if (load.hasErrors()) return Optional.of(tree("guards · jk-guards.toml does not load"));
            if (!LaneRun.judges(Lane.TREE, LaneRun.rulesFor(Lane.TREE, load.rules(), ""))) return Optional.empty();
            List<String> tokens = treeLaneTokens(root);
            addRuleTokens(tokens, load);
            String key = laneKey(
                    ActionKey.qualifiedTaskId(TaskNames.GUARD_TREE, root),
                    tokens,
                    baselineSlice(root, Lane.TREE, "", load.rules()));
            if (GuardVerdicts.lookup(actionCache, key, root).isPresent()) {
                return Optional.of(new TaskForecast.Task(
                        TaskNames.GUARD_TREE, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
            }
            return Optional.of(tree(missed(
                    actionCache, ActionKey.qualifiedTaskId(TaskNames.GUARD_TREE, root), "tree rules to evaluate")));
        } catch (IOException e) {
            return Optional.of(tree("guards · " + e.getMessage()));
        }
    }

    /**
     * Why lane {@code taskId} runs when its verdict misses: {@code jk changed} when its last verdict
     * on this disk was another jk's, since every lane key names the jk that judged it; else {@code
     * otherwise}.
     */
    static String missed(ActionCache actionCache, String taskId, String otherwise) {
        return "guards · " + (actionCache.madeByAnotherJk(taskId) ? StampTokens.JK_CHANGED : otherwise);
    }

    private static TaskForecast.Task tree(String why) {
        return new TaskForecast.Task(TaskNames.GUARD_TREE, TaskForecast.Status.RUN, why, "");
    }

    private static TaskForecast.Task workspace(String why) {
        return new TaskForecast.Task(TaskNames.GUARD_WORKSPACE, TaskForecast.Status.RUN, why, "");
    }

    /** The forecast's {@code guard-model} step at the invocation root, or empty. */
    static Optional<TaskForecast.Task> forecastModelLane(Path root, JkBuild rootBuild, ActionCache actionCache) {
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!g.enabled()) return Optional.empty();
        try {
            LoadResult load = PlannerGuards.rules(g);
            if (load.hasErrors()) return Optional.of(model("guards · jk-guards.toml does not load"));
            List<String> tokens = modelTokens(root, rootBuild);
            addRuleTokens(tokens, load);
            String key = laneKey(
                    ActionKey.qualifiedTaskId(TaskNames.GUARD_MODEL, root),
                    tokens,
                    baselineSlice(root, Lane.MODEL, "", load.rules()));
            if (GuardVerdicts.lookup(actionCache, key, root).isPresent()) {
                return Optional.of(new TaskForecast.Task(
                        TaskNames.GUARD_MODEL, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
            }
            return Optional.of(model(missed(
                    actionCache, ActionKey.qualifiedTaskId(TaskNames.GUARD_MODEL, root), "model rules to evaluate")));
        } catch (IOException e) {
            return Optional.of(model("guards · " + e.getMessage()));
        }
    }

    /** Manifest and lock digests, the model lane's read set; the lane and the forecast share it. */
    static List<String> modelTokens(Path root, JkBuild rootBuild) throws IOException {
        List<String> tokens = new ArrayList<>();
        tokens.add(fileToken("manifest", ManifestPaths.manifestIn(root)));
        // The build's expansion: a member a glob selects is read like a literal one.
        for (String m : expand(root, rootBuild.workspaceModules())) {
            tokens.add(fileToken("manifest:" + m, ManifestPaths.manifestIn(root.resolve(m))));
        }
        tokens.add(fileToken("lock", root.resolve(ManifestPaths.LOCK)));
        String installs = localInstallToken(root.resolve(ManifestPaths.LOCK));
        if (installs != null) tokens.add(installs);
        return tokens;
    }

    /**
     * A lock with rows this machine's installs served has two verdicts: a developer's build takes
     * them, and a CI build, {@code jk release} and {@code jk publish} refuse them ({@link
     * LocalInstallRows#shipping}). The key names which one this run asks for, so a CI build never
     * reads a developer's green; a lock with no such row keys as it did, its verdict shared by both.
     */
    static @Nullable String localInstallToken(Path lock) {
        try {
            if (!Files.isRegularFile(lock)
                    || LocalInstallRows.of(LockfileReader.read(lock)).isEmpty()) return null;
        } catch (IOException | RuntimeException unreadable) {
            return null; // the lane's own read of the lock reports it
        }
        String who = LocalInstallRows.shipping(
                BuildEnv::get, SessionContext.current().releaseVerb());
        return "local-install:" + (who == null ? "developer" : who);
    }

    /**
     * The gate's fixture proof: planned whenever the gate is asked for and any rule or guard test
     * names a fixture, and cached when its key — the rules, the fixtures, the suites and what the
     * fixtures compile against — names a green proof already.
     */
    static Optional<TaskForecast.Task> forecastFixtures(Path dir, ActionCache actionCache, Cas cas) {
        Path root = WorkspaceScan.findRoot(dir).orElse(dir).toAbsolutePath().normalize();
        PlannerGuards.GuardsPlan g = PlannerGuards.detectAt(root);
        if (!g.enabled() || !PlannerGuards.guardRequested()) return Optional.empty();
        try {
            LoadResult load = PlannerGuards.rules(g);
            boolean any = load.rules().rules().values().stream().anyMatch(r -> r.fixture() != null);
            if (!any) {
                for (GuardSuites.Located l :
                        GuardSuites.declaredAcrossWorkspace(root).values()) {
                    if (l.declared().fixture() != null) any = true;
                }
            }
            if (!any) return Optional.empty();
            List<String> tokens = GuardFixtures.keyTokens(root, cas);
            if (tokens != null) {
                String key = laneKey(ActionKey.qualifiedTaskId(TaskNames.GUARD_FIXTURES, root), tokens, "fixtures");
                if (actionCache.lookup(key).isPresent()) {
                    return Optional.of(new TaskForecast.Task(
                            TaskNames.GUARD_FIXTURES, TaskForecast.Status.CACHED, "", key.substring(0, 8)));
                }
            }
            return Optional.of(new TaskForecast.Task(
                    TaskNames.GUARD_FIXTURES,
                    TaskForecast.Status.RUN,
                    "guards · fixtures prove every rule bites",
                    null));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static String fileToken(String name, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return name + ":absent";
        return name + ":" + FileHashMemo.contentHash(file);
    }

    private static TaskForecast.Task run(String text) {
        return new TaskForecast.Task(TaskNames.GUARD, TaskForecast.Status.RUN, text, null);
    }

    private static TaskForecast.Task model(String text) {
        return new TaskForecast.Task(TaskNames.GUARD_MODEL, TaskForecast.Status.RUN, text, null);
    }
}
