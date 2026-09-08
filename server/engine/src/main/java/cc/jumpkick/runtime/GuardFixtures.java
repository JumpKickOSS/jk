// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.guard.eval.FixtureCheck;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.eval.Outcome;
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
import cc.jumpkick.model.Scope;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
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
 * {@code Ok} must not. Text rules are judged over their snippet files. The load-time validation of
 * every rule and suite runs first and reports every error.
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
            cfg = JkBuildParser.guardsConfig(root.resolve(ManifestPaths.MANIFEST));
        } catch (RuntimeException e) {
            cfg = GuardsConfig.ABSENT;
        }
        LoadResult load = GuardRules.load(root, cfg);
        List<String> loadErrors = new ArrayList<>();
        for (LoadError e : load.errors()) loadErrors.add(e.render());
        Map<String, GuardSuites.Located> guards = GuardSuites.declaredAcrossWorkspace(root);
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
            if (!c.compiled()) {
                verdicts.add(FixtureCheck.textVerdict(c, FixtureCheck.sources(c.dir())));
                continue;
            }
            byModule.computeIfAbsent(c.module(), k -> new ArrayList<>()).add(c);
        }
        for (var e : byModule.entrySet()) verdicts.addAll(compiledVerdicts(root, e.getKey(), e.getValue(), cas));
        verdicts.sort((a, b) -> a.id().compareTo(b.id()));
        return new Result(loadErrors, verdicts, FixtureCheck.render(verdicts, loadErrors));
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
                badSites = guardSites(root, module, moduleDir, c, badSlice, badText, work.resolve("bad"), cas);
                okSites = okFiles == 0
                        ? 0
                        : guardSites(root, module, moduleDir, c, okSlice, okText, work.resolve("ok"), cas);
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

    /**
     * Run the guard test's suite over one fixture slice; the number of sites it reported, or -1. The
     * slice's files are copied under {@code work/text} so the guard's text view sees Bad without Ok
     * and Ok without Bad.
     */
    private static int guardSites(
            Path root,
            String module,
            Path moduleDir,
            FixtureCheck.Case c,
            FactsIndex slice,
            List<Path> textFiles,
            Path work,
            Cas cas)
            throws IOException {
        Path text = work.resolve("text");
        PathUtil.deleteRecursivelyOrThrow(text);
        Files.createDirectories(text);
        for (Path f : textFiles) Files.copy(f, text.resolve(f.getFileName().toString()));
        Path idx = work.resolve("main-guard.idx");
        FactsFormat.write(idx, slice);
        JkBuild build = JkBuildParser.parse(moduleDir.resolve(ManifestPaths.MANIFEST));
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
                List.of(text));
        try {
            List<String> problems = GuardSuiteRunner.run(in, List.of(moduleDir), report);
            if (!problems.isEmpty()) return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
        Object line = GuardSuites.readReport(report).get(c.id());
        if (line == null) return -1;
        var eval = GuardSuites.evaluate(c.rule(), line, module);
        return eval.observations().size();
    }

    private static Path javaHome() {
        return Path.of(System.getProperty("java.home"));
    }

    /** The owning module's main compile classpath plus its own classes; empty when nothing is locked yet. */
    static List<Path> compileClasspath(Path root, Path moduleDir, Cas cas) throws IOException {
        List<Path> cp = new ArrayList<>();
        Path manifest = moduleDir.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(manifest)) return cp;
        JkBuild build = JkBuildParser.parse(manifest);
        cp.add(BuildLayout.of(moduleDir, build).classesDir());
        Path lockFile = root.resolve(ManifestPaths.LOCK);
        if (Files.isRegularFile(lockFile)) {
            Lockfile lock = LockfileReader.read(lockFile);
            WorkspaceClasspath.Result siblings =
                    WorkspaceClasspath.resolve(moduleDir, build, Set.of(Scope.EXPORT, Scope.MAIN));
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
        for (Path p : classpath) key.append("cp:").append(p).append('\n');
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
