// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.guard.eval.Evaluation;
import cc.jumpkick.guard.eval.FixtureCheck;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.eval.Outcome;
import cc.jumpkick.guard.eval.WorkspaceModel;
import cc.jumpkick.guard.eval.WorkspaceModules;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.LoadError;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk guard test}: prove every fixture-bearing rule and guard test bites. Every fixture
 * directory of one owning module is compiled in a single javac call against that module's compile
 * classpath (keyed by the fixture sources and the classpath; unchanged fixtures skip the compile),
 * indexed once, and each rule is judged over its own fixture's classes: {@code Bad} must fire,
 * {@code Ok} must not. Text rules are judged over their snippet files. A guard test whose fixture
 * holds {@code Bad*}/{@code Ok*} directories is run once per case, the suite's text view rooted at
 * the case as if it were the checkout. The load-time validation of every rule and suite runs first
 * and reports every error.
 */
public final class GuardFixtures {

    /** javac invocations this JVM made, for the one-per-module claim. */
    static final AtomicInteger COMPILES = new AtomicInteger();

    private GuardFixtures() {}

    public record Result(List<String> loadErrors, List<FixtureCheck.Verdict> verdicts, String text) {
        public boolean ok() {
            return loadErrors.isEmpty() && verdicts.stream().allMatch(FixtureCheck.Verdict::ok);
        }

        public int failures() {
            return loadErrors.size()
                    + (int) verdicts.stream().filter(v -> !v.ok()).count();
        }
    }

    public static Result run(Path root, Cas cas) throws IOException {
        GuardsConfig cfg;
        try {
            cfg = JkBuildParser.guardsConfig(ManifestPaths.manifestIn(root));
        } catch (RuntimeException e) {
            cfg = GuardsConfig.ABSENT;
        }
        LoadResult load = GuardRules.load(root, cfg);
        List<String> loadErrors = new ArrayList<>();
        for (LoadError e : load.errors()) loadErrors.add(e.render());
        Map<String, GuardSuites.Located> guards = GuardSuites.declaredAcrossWorkspace(root);
        loadErrors.addAll(unindexedSuites(root));
        List<GuardSuites.Declared> declared = new ArrayList<>();
        for (GuardSuites.Located g : guards.values()) declared.add(g.declared());
        loadErrors.addAll(GuardSuites.loadErrors(declared, load.rules()));
        if (!load.errors().isEmpty()) {
            return new Result(loadErrors, List.of(), FixtureCheck.render(List.of(), loadErrors));
        }
        List<FixtureCheck.Case> cases = FixtureCheck.cases(root, load.rules(), guards);
        List<FixtureCheck.Verdict> verdicts = new ArrayList<>();
        // text rules: snippets, no compile
        Map<String, List<FixtureCheck.Case>> byModule = new LinkedHashMap<>();
        for (FixtureCheck.Case c : cases) {
            if (!Files.isDirectory(c.dir())) {
                verdicts.add(new FixtureCheck.Verdict(
                        c.id(), "error", "fixture directory " + root.relativize(c.dir()) + " does not exist"));
                continue;
            }
            List<FixtureCheck.Source> sources = FixtureCheck.sources(c.dir());
            if (sources.stream().anyMatch(FixtureCheck.Source::tree)) {
                verdicts.add(treeVerdict(root, c, sources, cas));
                continue;
            }
            if (!c.compiled()) {
                verdicts.add(FixtureCheck.textVerdict(c, sources));
                continue;
            }
            byModule.computeIfAbsent(c.module(), k -> new ArrayList<>()).add(c);
        }
        for (var e : byModule.entrySet()) verdicts.addAll(compiledVerdicts(root, e.getKey(), e.getValue(), cas));
        verdicts.sort((a, b) -> a.id().compareTo(b.id()));
        return new Result(loadErrors, verdicts, FixtureCheck.render(verdicts, loadErrors));
    }

    /**
     * Modules whose {@code src/guard} suite has no compiled index. Guard tests and their fixtures are
     * discovered from {@code target/incremental/guard-guard.idx}, which {@code jk guard} (or a {@code
     * --guard} build) writes when it compiles the suite; a standalone {@code jk guard test} on a
     * checkout that has run neither would otherwise report the suite's fixtures as simply absent.
     * Each is a load error naming the module and the command that writes the index.
     */
    static List<String> unindexedSuites(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        List<Path> modules = new ArrayList<>();
        modules.add(root);
        for (Path m : WorkspaceModules.of(root)) if (!modules.contains(m)) modules.add(m);
        for (Path m : modules) {
            // Both layouts, as the source-side declaration scan reads them.
            if (!Files.isDirectory(m)
                    || (!PlannerGuardSuite.declared(m, false) && !PlannerGuardSuite.declared(m, true))) continue;
            Path idx = FactsIndexing.indexPath(BuildLayout.moduleTargetDir(root, m), "guard");
            if (Files.isRegularFile(idx)) continue;
            String rel = WorkspaceModel.rel(root, m);
            out.add((rel.isEmpty() ? "the workspace root" : rel)
                    + " declares a guard suite under src/guard and its compiled index "
                    + root.relativize(idx).toString().replace('\\', '/')
                    + " is missing — guard tests and their fixtures are discovered from the compiled suite; run"
                    + " `jk guard` (or `jk build --guard`) first, then `jk guard test`");
        }
        return out;
    }

    /**
     * A guard test's tree fixture: each {@code Bad*}/{@code Ok*} directory, laid over the files beside
     * them, is run as the checkout root — one fork of the suite per case, no facts — and the sites the
     * guard reported over it count as that case's.
     */
    static FixtureCheck.Verdict treeVerdict(Path root, FixtureCheck.Case c, List<FixtureCheck.Source> sources, Cas cas)
            throws IOException {
        if (!c.guardTest())
            return new FixtureCheck.Verdict(
                    c.id(), "error", "a tree fixture needs a guard test; a TOML rule is judged over Bad*/Ok* files");
        String mixed = FixtureCheck.treeProblem(sources);
        if (mixed != null) return new FixtureCheck.Verdict(c.id(), "error", mixed);
        Path moduleDir = c.module().isEmpty() ? root : root.resolve(c.module());
        Path work = BuildLayout.moduleTargetDir(root, moduleDir)
                .resolve("jk-guards")
                .resolve("fixtures")
                .resolve(c.id());
        FactsIndex none = new FactsIndex(Map.of(), Map.of(), "tree:" + c.id());
        int badCases = 0;
        int okCases = 0;
        int badSites = 0;
        int okSites = 0;
        for (FixtureCheck.Source s : sources) {
            Path caseWork = work.resolve(s.file().getFileName().toString());
            Path tree = caseWork.resolve("tree");
            FixtureCheck.caseTree(c.dir(), s, tree);
            Evaluation eval = guardEvaluation(root, c.module(), moduleDir, c, none, tree, true, caseWork, cas);
            if (eval == null)
                return new FixtureCheck.Verdict(
                        c.id(),
                        "error",
                        "the guard suite did not run over " + s.file().getFileName() + "; see the lane's diagnostics");
            // The guard could not run here (its tool is not installed): the case is the notice the
            // lane reports, not a silence of the guard's.
            if (eval.outcome() == Outcome.SKIPPED)
                return new FixtureCheck.Verdict(
                        c.id(), FixtureCheck.Verdict.SKIPPED, s.file().getFileName() + ": " + eval.note());
            int sites = eval.observations().size();
            if (s.bad()) {
                badCases++;
                badSites += sites;
            } else {
                okCases++;
                okSites += sites;
            }
        }
        return FixtureCheck.verdict(c.id(), badCases, badSites, okCases, okSites);
    }

    /** One module's fixtures: one javac, one index, one verdict per case. */
    static List<FixtureCheck.Verdict> compiledVerdicts(Path root, String module, List<FixtureCheck.Case> cases, Cas cas)
            throws IOException {
        Path moduleDir = module.isEmpty() ? root : root.resolve(module);
        Map<FixtureCheck.Case, List<FixtureCheck.Source>> sources = new LinkedHashMap<>();
        // Stub types a fixture carries sit in both slices, so a signature naming them resolves; they
        // are never a site of their own.
        Map<FixtureCheck.Case, Set<String>> stubs = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        Map<String, String> owners = new LinkedHashMap<>(); // class → fixture id, for collisions
        List<FixtureCheck.Verdict> out = new ArrayList<>();
        for (FixtureCheck.Case c : cases) {
            List<FixtureCheck.Source> src = FixtureCheck.sources(c.dir());
            sources.put(c, src);
            // Every source in the directory compiles — a fixture may carry stub types (a framework
            // annotation by its real name) so it needs nothing on the classpath; only Bad*/Ok* are judged.
            Set<String> stubClasses = new TreeSet<>();
            if (Files.isDirectory(c.dir())) {
                PathUtil.forEachRegularFile(c.dir(), (f, attrs) -> {
                    String n = f.getFileName().toString();
                    if (!n.endsWith(".java") || n.startsWith("Bad") || n.startsWith("Ok")) return;
                    if (!files.contains(f)) files.add(f);
                    stubClasses.addAll(FixtureCheck.declaredClasses(Files.readString(f, StandardCharsets.UTF_8)));
                });
            }
            stubs.put(c, stubClasses);
            for (FixtureCheck.Source s : src) {
                if (!s.file().toString().endsWith(".java")) continue;
                files.add(s.file());
                for (String cls : s.classes()) {
                    String other = owners.putIfAbsent(cls, c.id());
                    if (other != null && !other.equals(c.id())) {
                        out.add(new FixtureCheck.Verdict(
                                c.id(),
                                "error",
                                "fixture class " + cls + " is also declared by fixture `" + other
                                        + "`; give each fixture its own package"));
                    }
                }
            }
        }
        if (files.isEmpty()) {
            for (FixtureCheck.Case c : cases)
                out.add(new FixtureCheck.Verdict(c.id(), "error", "the fixture has no Bad*.java or Ok*.java"));
            return out;
        }
        List<Path> classpath = compileClasspath(root, moduleDir, cas);
        Path outDir = BuildLayout.moduleTargetDir(root, moduleDir)
                .resolve("jk-guards")
                .resolve("fixtures")
                .resolve("classes");
        String problem = compile(files, classpath, outDir);
        if (problem != null) {
            for (FixtureCheck.Case c : cases)
                out.add(new FixtureCheck.Verdict(c.id(), "error", "fixtures did not compile: " + problem));
            return out;
        }
        FactsIndexing.Ensured ensured = FactsIndexing.ensure(outDir, outDir.resolveSibling("fixtures-guard.idx"));
        FactsIndex all = FactsIndexing.load(ensured);
        for (FixtureCheck.Case c : cases) {
            if (out.stream().anyMatch(v -> v.id().equals(c.id()))) continue;
            Set<String> bad = new TreeSet<>();
            Set<String> ok = new TreeSet<>();
            List<Path> badText = new ArrayList<>();
            List<Path> okText = new ArrayList<>();
            int badFiles = 0;
            int okFiles = 0;
            for (FixtureCheck.Source s : sources.getOrDefault(c, List.of())) {
                if (s.bad()) {
                    badFiles++;
                    bad.addAll(s.classes());
                    badText.add(s.file());
                } else {
                    okFiles++;
                    ok.addAll(s.classes());
                    okText.add(s.file());
                }
            }
            bad.addAll(stubs.getOrDefault(c, Set.of()));
            ok.addAll(stubs.getOrDefault(c, Set.of()));
            FactsIndex badSlice = FixtureCheck.slice(all, bad);
            FactsIndex okSlice = FixtureCheck.slice(all, ok);
            int badSites;
            int okSites;
            if (c.guardTest()) {
                Path work = BuildLayout.moduleTargetDir(root, moduleDir)
                        .resolve("jk-guards")
                        .resolve("fixtures")
                        .resolve(c.id());
                Path badWork = work.resolve("bad");
                Path okWork = work.resolve("ok");
                badSites = guardSites(
                        root, module, moduleDir, c, badSlice, sliceText(badWork, badText), false, badWork, cas);
                okSites = okFiles == 0
                        ? 0
                        : guardSites(
                                root, module, moduleDir, c, okSlice, sliceText(okWork, okText), false, okWork, cas);
                if (badSites < 0 || okSites < 0) {
                    out.add(new FixtureCheck.Verdict(
                            c.id(),
                            "error",
                            "the guard suite did not run over the fixture; see the lane's diagnostics"));
                    continue;
                }
            } else {
                var badEval = FixtureCheck.evaluate(c, root, badSlice, c.rule().kind() == Kind.TIERS ? badSlice : null);
                var okEval = FixtureCheck.evaluate(c, root, okSlice, c.rule().kind() == Kind.TIERS ? okSlice : null);
                // Anything but a verdict over the slice (blind, owner-missing, not-evaluated, a
                // scanner failure) is the rule's condition, not the fixture's silence: say which.
                if (badEval.outcome() != Outcome.CLEAN && badEval.outcome() != Outcome.VIOLATIONS) {
                    out.add(new FixtureCheck.Verdict(
                            c.id(),
                            "error",
                            "Bad evaluated " + badEval.outcome().id() + ": " + badEval.note()));
                    continue;
                }
                badSites = badEval.observations().size();
                okSites = okEval.observations().size();
            }
            out.add(FixtureCheck.verdict(c.id(), badFiles, badSites, okFiles, okSites));
        }
        return out;
    }

    /** The slice's files copied under {@code work/text}, so the guard's text view sees Bad without Ok and Ok without Bad. */
    private static Path sliceText(Path work, List<Path> textFiles) throws IOException {
        Path text = work.resolve("text");
        PathUtil.deleteRecursivelyOrThrow(text);
        Files.createDirectories(text);
        for (Path f : textFiles) Files.copy(f, text.resolve(f.getFileName().toString()));
        return text;
    }

    /** As {@link #guardEvaluation}; the number of sites the guard reported, or -1 when the suite did not run. */
    private static int guardSites(
            Path root,
            String module,
            Path moduleDir,
            FixtureCheck.Case c,
            FactsIndex slice,
            Path text,
            boolean tree,
            Path work,
            Cas cas)
            throws IOException {
        Evaluation eval = guardEvaluation(root, module, moduleDir, c, slice, text, tree, work, cas);
        return eval == null ? -1 : eval.observations().size();
    }

    /**
     * Run the guard test's suite over one fixture slice; the guard's evaluation over it, or {@code
     * null} when the suite did not run. {@code text} is what the guard's text view reads: the slice's
     * files by name, or — {@code tree} — a case directory the view is rooted at, so the guard reads it
     * as the checkout.
     */
    private static @Nullable Evaluation guardEvaluation(
            Path root,
            String module,
            Path moduleDir,
            FixtureCheck.Case c,
            FactsIndex slice,
            Path text,
            boolean tree,
            Path work,
            Cas cas)
            throws IOException {
        Path idx = work.resolve("main-guard.idx");
        FactsFormat.write(idx, slice);
        JkBuild build = JkBuildParser.parse(ManifestPaths.manifestIn(moduleDir));
        BuildLayout layout = BuildLayout.of(moduleDir, build);
        Path lockFile = root.resolve(ManifestPaths.LOCK);
        Lockfile lock = Files.isRegularFile(lockFile) ? LockfileReader.read(lockFile) : null;
        ClasspathResolver resolver = new ClasspathResolver(cas);
        List<Path> testRuntime = new ArrayList<>();
        if (lock != null) testRuntime.addAll(resolver.classpathFor(lock, ClasspathResolver.TEST, false));
        Path library = GuardSuiteLibrary.locate(root, cas).path();
        List<Path> runtimeCp = PlannerGuardSuite.classpath(build, layout, testRuntime, library);
        Path report = GuardSuites.report(work);
        GuardSuiteRunner.Inputs in = new GuardSuiteRunner.Inputs(
                root,
                module,
                moduleDir,
                layout,
                javaHome(),
                runtimeCp,
                cas.root(),
                List.of(idx),
                List.of(),
                List.of(),
                false,
                List.of(text),
                tree ? text : null);
        try {
            List<String> problems = GuardSuiteRunner.run(in, List.of(moduleDir), report);
            if (!problems.isEmpty()) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        Object line = GuardSuites.readReport(report).get(c.id());
        if (line == null) return null;
        return GuardSuites.evaluate(c.rule(), line, module);
    }

    private static Path javaHome() {
        return Path.of(System.getProperty("java.home"));
    }

    /** The owning module's main compile classpath plus its own classes; empty when nothing is locked yet. */
    static List<Path> compileClasspath(Path root, Path moduleDir, Cas cas) throws IOException {
        List<Path> cp = new ArrayList<>();
        Path manifest = ManifestPaths.manifestIn(moduleDir);
        if (!Files.isRegularFile(manifest)) return cp;
        JkBuild build = JkBuildParser.parse(manifest);
        cp.add(BuildLayout.of(moduleDir, build).classesDir());
        Path lockFile = root.resolve(ManifestPaths.LOCK);
        if (Files.isRegularFile(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            WorkspaceClasspath.Result siblings =
                    WorkspaceClasspath.resolve(moduleDir, build, WorkspaceClasspath.COMPILE_SCOPES);
            for (Path p : PlannerSupport.mainCompileClasspath(lock, new ClasspathResolver(cas), siblings))
                if (!cp.contains(p)) cp.add(p);
        }
        return cp;
    }

    /** One javac for the files, skipped when the key file says these exact sources and classpath were compiled before. */
    static @Nullable String compile(List<Path> files, List<Path> classpath, Path outDir) throws IOException {
        StringBuilder key = new StringBuilder();
        for (Path f : files)
            key.append(f).append('=').append(Hashing.sha256Hex(f)).append('\n');
        // Classpath entries key by content, not by path: the module's own classes dir keeps its
        // path across every build, and fixtures compiled against last build's bytecode would
        // otherwise be reused after the module changed under them.
        for (Path p : classpath)
            key.append("cp:").append(ClasspathFingerprint.entry(p)).append('\n');
        String digest = Hashing.sha256Hex(key.toString().getBytes(StandardCharsets.UTF_8));
        Path keyFile = outDir.resolveSibling("fixtures.key");
        if (Files.isRegularFile(keyFile)
                && Files.isDirectory(outDir)
                && digest.equals(Files.readString(keyFile).strip())) return null;
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) return "no javac in the engine's JDK";
        if (Files.isDirectory(outDir)) PathUtil.deleteRecursivelyOrThrow(outDir);
        Files.createDirectories(outDir);
        List<String> args = new ArrayList<>(List.of("-d", outDir.toString(), "-proc:none", "-nowarn", "-Xlint:none"));
        if (!classpath.isEmpty()) {
            args.add("-cp");
            args.add(Classpaths.join(classpath));
        }
        for (Path f : files) args.add(f.toString());
        StringWriter err = new StringWriter();
        COMPILES.incrementAndGet();
        int rc = javac.run(
                null,
                null,
                new PrintStream(
                        new OutputStream() {
                            @Override
                            public void write(int b) {
                                err.write(b);
                            }
                        },
                        true,
                        StandardCharsets.UTF_8),
                args.toArray(String[]::new));
        if (rc != 0) return err.toString().strip().lines().findFirst().orElse("javac exit " + rc);
        Files.writeString(keyFile, digest);
        return null;
    }
}
