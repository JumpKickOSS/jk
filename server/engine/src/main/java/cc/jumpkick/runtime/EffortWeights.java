// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.FreshnessStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Predicts each build step's progress-bar weight from on-disk state at pipeline start. Skip
 * detection uses {@link FreshnessStamp#looksFresh}; compile→test→package are correlated (if compile
 * will run, consumers are reserved too). Fresh/cached steps reserve {@link #TOKEN} (not zero) so
 * the aggregate bar keeps a denominator (JK-1153). Mispredictions only mis-size a slice — closed
 * by step-end auto-fill. See {@code docs/perf/progress-contract.md}.
 */
public final class EffortWeights {

    private EffortWeights() {}

    /**
     * Plan/runtime weight when a step is known skip/cache-hit but still appears in the pipeline
     * (JK-1153). Keeps a non-zero phase tick so the aggregate bar has a denominator without
     * inventing full compile/test cost.
     */
    public static final int TOKEN = 1;

    /** Omitted / no contribution. Prefer {@link #TOKEN} when the step still runs a short check. */
    static final int SKIP = 0;

    static final int RESTORE = 3;
    /** Cold static reservation for test JVM fork + framework init. */
    static final int TEST_STARTUP = 15;

    static final int TEST_METHOD = 8;
    /**
     * Learnable fixed floor for run-tests — must stay below typical hot-suite residual so samples
     * are not dropped (unlike the larger cold {@link #TEST_STARTUP}).
     */
    static final int TEST_STARTUP_FLOOR = 2;

    static final int COMPILE_FLOOR = 2;
    static final int ARTIFACT_FETCH = 8;

    /** Weight unit ≈150 ms — {@code Pipeline} interpolation constant. */
    public static final int MS_PER_WEIGHT = 150;

    static final int PACKAGE_JAR = 5;
    static final int JDK_DOWNLOAD = 70;
    static final int ASSEMBLY_RUN = 10;
    static final int NATIVE_RUN = 100;
    static final int OCI_RUN = 40;
    static final int OCI_SKIP = 2;

    /**
     * Per-step predicted weights for one module. {@code fullyCached} means every work step is
     * {@link #SKIP} — always-run steps should shrink to a token touch.
     */
    public record Plan(
            int sync,
            int compileJava,
            int compileKotlin,
            int compileGroovy,
            int compileTest,
            int runTests,
            int pkg,
            boolean fullyCached) {}

    /** {@code ceil(sources × 0.1)}, floored at 1 once the step runs at all. */
    static int compileWeight(int sources) {
        return Math.max(1, (sources + 9) / 10);
    }

    /** Cold test-step weight: {@link #TEST_STARTUP} plus per-method term. */
    public static int runTestsWeight(int methods) {
        return TEST_STARTUP + Math.max(0, methods) * TEST_METHOD;
    }

    /**
     * Hierarchical test weight (JK-1152 MVP): prefer learned module rate × method count; else
     * class-count × derived class rate; else method static floor. Does not replace
     * {@link #learned} — callers compose: {@code learned(..., runTestsHierarchical(...))}.
     */
    public static int runTestsHierarchical(int methods, int classes) {
        int m = Math.max(0, methods);
        int c = Math.max(0, classes);
        if (m > 0) return runTestsWeight(m);
        if (c > 0) return TEST_STARTUP + c * (TEST_METHOD * 3); // ~3 methods/class cold guess
        return TEST_STARTUP;
    }

    /**
     * Phase rollup: sum of flat learned step weights for a module (compile + test + package).
     * Missing steps contribute 0. Used for module-level schedule costs when shape memo is cold.
     */
    public static int phaseRollupWeight(String dir, BuildMetrics metrics) {
        if (metrics == null) metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        int sum = 0;
        for (String step : List.of(
                "compile-java",
                "compile-kotlin",
                "compile-groovy",
                "compile-test",
                "run-tests",
                "package-jar",
                "resolve-deps",
                "copy-resources")) {
            var e = metrics.step(dir == null ? "" : dir, step);
            if (e.isPresent() && e.get().ok().count() >= MIN_METRICS_SAMPLES) {
                sum += flatWeight(e.get().ok().avgMillis());
            }
        }
        return sum;
    }

    /** Learnable startup floor subtracted before recording a per-unit rate. */
    static int floor(String step) {
        return switch (step) {
            case "run-tests" -> TEST_STARTUP_FLOOR;
            case "compile-java", "compile-kotlin", "compile-groovy", "compile-test" -> COMPILE_FLOOR;
            default -> 0;
        };
    }

    /** Minimum successful runs before a metrics average outranks a static constant. */
    static final int MIN_METRICS_SAMPLES = 3;

    /**
     * Metrics-learned flat weight for fixed-cost steps: module avg → host avg → {@code
     * staticWeight}. Success-only samples.
     */
    static int learnedFixedWeight(String dir, String step, int staticWeight) {
        return learnedFixedWeight(BuildMetrics.load(BuildMetrics.defaultFile()), dir, step, staticWeight);
    }

    static int learnedFixedWeight(BuildMetrics metrics, String dir, String step, int staticWeight) {
        var own = metrics.step(dir, step);
        if (own.isPresent() && own.get().ok().count() >= MIN_METRICS_SAMPLES) {
            return flatWeight(own.get().ok().avgMillis());
        }
        var host = metrics.step("", step);
        if (host.isPresent() && host.get().ok().count() >= MIN_METRICS_SAMPLES) {
            return flatWeight(host.get().ok().avgMillis());
        }
        return staticWeight;
    }

    /** A whole-step historical average (ms) as a flat bar weight. */
    private static int flatWeight(long avgMillis) {
        return Math.max(1, (int) Math.round(avgMillis / (double) MS_PER_WEIGHT));
    }

    /** Back-compat overload with no project context — a two-tier fallback (module → host-median). */
    static int learned(StepTimings timings, String dir, String step, int count, int staticWeight) {
        return learned(timings, dir, step, count, staticWeight, java.util.List.of());
    }

    /**
     * Learned weight {@code floor + rate × count}. Rate preference: this module → project median →
     * host median → {@link BuildMetrics} flat avg (survives {@code jk clean}) → {@code
     * staticWeight}.
     */
    static int learned(
            StepTimings timings,
            String dir,
            String step,
            int count,
            int staticWeight,
            java.util.Collection<String> projectDirs) {
        return learned(
                timings, BuildMetrics.load(BuildMetrics.defaultFile()), dir, step, count, staticWeight, projectDirs);
    }

    static int learned(
            StepTimings timings,
            BuildMetrics metrics,
            String dir,
            String step,
            int count,
            int staticWeight,
            java.util.Collection<String> projectDirs) {
        double rate;
        var own = timings.perUnit(dir, step);
        if (own.isPresent()) {
            rate = own.getAsDouble();
        } else {
            var project = timings.medianPerUnit(step, projectDirs);
            if (project.isPresent()) {
                rate = project.getAsDouble();
            } else {
                var host = timings.medianPerUnit(step);
                if (host.isEmpty()) {
                    // No rate anywhere (cold ledger, e.g. right after `jk clean`) — fall back to the
                    // surviving metrics history before conceding to the Step-1 static guess.
                    return learnedFixedWeight(metrics, dir, step, staticWeight);
                }
                rate = host.getAsDouble();
            }
        }
        return Math.max(1, (int) Math.round(floor(step) + rate * Math.max(0, count)));
    }

    /**
     * Per-unit residual to teach the ledger; negative when duration ≤ floor (skip/cache hit — do
     * not learn a ~0 rate).
     */
    public static double observedPerUnit(String step, long durationMs, int count) {
        double actualWeight = durationMs / (double) MS_PER_WEIGHT;
        double residual = actualWeight - floor(step);
        if (residual <= 0) return -1;
        return residual / Math.max(1, count);
    }

    /** Predict the weights for {@code in}; never throws (degrades to skip-ish). */
    public static Plan predict(BuildPipelines.Inputs in, Cas cas, boolean compact, boolean useJava, boolean useKotlin) {
        return predict(in, cas, compact, useJava, useKotlin, false, false);
    }

    /** Back-compat overload (no Groovy lane). */
    public static Plan predict(
            BuildPipelines.Inputs in,
            Cas cas,
            boolean compact,
            boolean useJava,
            boolean useKotlin,
            boolean forceRebuild) {
        return predict(in, cas, compact, useJava, useKotlin, false, forceRebuild);
    }

    /**
     * Predict weights; {@code forceRebuild} reserves full compile/test/package even when stamps
     * look fresh (upstream dirty). Over-reserves only — runtime reweight can shrink, never grow.
     */
    public static Plan predict(
            BuildPipelines.Inputs in,
            Cas cas,
            boolean compact,
            boolean useJava,
            boolean useKotlin,
            boolean useGroovy,
            boolean forceRebuild) {
        boolean rerun = in.session().config().rebuildOr(false) || forceRebuild;
        // If jk.toml is newer than jk.lock AND the lock no longer satisfies all declared
        // deps, treat the module as dirty so parse-lock runs and updates the lock.
        boolean lockStale = !rerun && AutoLock.needsRelocking(in.dir(), in.lockFile());
        if (lockStale) rerun = true;

        int sync = predictSync(in, cas);
        // Learned per-unit rates (cold ⇒ empty ⇒ static Step-1 weights). Keyed by
        // module dir, the same key the recorder writes at build end.
        StepTimings timings = StepTimings.load(in.cache());
        String mod = in.dir().toString();
        // Sibling modules of this build, for the project-tier learned fallback (empty ⇒ host-median).
        List<String> projectDirs =
                in.projectModules().stream().map(Path::toString).toList();
        int compileJava = SKIP, compileKotlin = SKIP, compileGroovy = SKIP, compileTest = SKIP, runTests = SKIP;
        int pkg = SKIP;
        List<Path> testSrc = new ArrayList<>();
        boolean hadTestSources = false;
        try {
            JkBuild project = JkBuildParser.parse(in.buildFile());
            BuildLayout layout = BuildLayout.of(in.dir(), project);

            boolean javaRun = false;
            if (useJava) {
                List<Path> src = CompileSupport.collectJavaSources(
                        compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java"));
                javaRun = rerun || !FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.JAVA_STAMP, src);
                compileJava = javaRun
                        ? learned(timings, mod, "compile-java", src.size(), compileWeight(src.size()), projectDirs)
                        : SKIP;
            }
            boolean ktRun = false;
            if (useKotlin) {
                List<Path> src = CompileSupport.collectKotlinSources(in.dir(), compact);
                ktRun = rerun
                        || !FreshnessStamp.looksFresh(layout.kotlinClassesDir(), FreshnessStamp.KOTLIN_STAMP, src);
                compileKotlin = ktRun
                        ? learned(timings, mod, "compile-kotlin", src.size(), compileWeight(src.size()), projectDirs)
                        : SKIP;
            }
            boolean gvRun = false;
            if (useGroovy) {
                // The groovy stamp lives in the merged classes dir — that is where
                // write-stamp-groovy writes it (stamp-only freshness, like Kotlin's).
                List<Path> src = CompileSupport.collectGroovySources(in.dir(), compact);
                gvRun = rerun || !FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.GROOVY_STAMP, src);
                compileGroovy = gvRun
                        ? learned(timings, mod, "compile-groovy", src.size(), compileWeight(src.size()), projectDirs)
                        : SKIP;
            }
            boolean compileRun = javaRun || ktRun || gvRun;

            // Tests + packaging consume the compiled output: if a compile ran (or
            // --force), they run. The precise test skip is decided at run-tests via
            // the CAS marker (which survives `jk clean`); that step reweights down
            // to TOKEN there (JK-1153).
            try {
                testSrc.addAll(TestSupport.collectAllSuiteTestSources(in.dir(), compact));
            } catch (IOException e) {
                // fall back to default suite only
                testSrc.addAll(CompileSupport.collectJavaSources(
                        compact ? in.dir().resolve("test") : in.dir().resolve("src/test/java")));
                testSrc.addAll(CompileSupport.collectKotlinTestSources(in.dir(), compact));
            }
            hadTestSources = !testSrc.isEmpty();
            boolean testWillRun = hadTestSources && (rerun || compileRun);
            // compile-test is an opaque, batch javac/kotlinc call: the step declares
            // .ticks(1), so the recorder learns its rate against a count of 1 — i.e. the
            // learned value IS the whole-step weight, not a per-file rate. Forecast it
            // with count=1 to match (a flat per-step cost). Multiplying it by the test
            // file count here was the bug that ballooned the estimate (e.g. ×46 → ~40s
            // of pure fiction for a ~1.2s compile). The static cold fallback stays a
            // file-count guess. (compile-java is consistent: its .ticks is the source
            // count, the same count predict multiplies, so it stays per-source.)
            compileTest = testWillRun
                    ? learned(timings, mod, "compile-test", 1, compileWeight(testSrc.size()), projectDirs)
                    : SKIP;

            int methods = in.estimatedTestCount();
            int classes = TestSupport.estimateAllSuiteTestClassCount(in.dir(), compact);
            int staticTests = runTestsHierarchical(methods, classes);
            // JK-1155: prefer method-count × run-tests rate; fall back to class-count ×
            // run-tests-class rate when method annotations are not found.
            if (testWillRun) {
                if (methods > 0) {
                    runTests = learned(timings, mod, "run-tests", methods, staticTests, projectDirs);
                } else if (classes > 0) {
                    runTests = learned(timings, mod, "run-tests-class", classes, staticTests, projectDirs);
                } else {
                    runTests = learned(timings, mod, "run-tests", 1, staticTests, projectDirs);
                }
            } else {
                runTests = SKIP;
            }

            boolean jarFresh = !rerun && !compileRun && Files.isRegularFile(layout.mainJar());
            pkg = jarFresh ? SKIP : learnedFixedWeight(mod, "package-jar", PACKAGE_JAR);
        } catch (Exception ignored) {
            // Unparseable project / layout — parse-build will surface the real
            // error; skip-ish weights + auto-fill keep the bar honest meanwhile.
        }
        // Fresh steps still sit in the pipeline for a stamp check — reserve a token so the
        // workspace bar never calibrates to a pure-zero execute band (JK-1153).
        if (useJava && compileJava == SKIP) compileJava = TOKEN;
        if (useKotlin && compileKotlin == SKIP) compileKotlin = TOKEN;
        if (useGroovy && compileGroovy == SKIP) compileGroovy = TOKEN;
        if (compileTest == SKIP && hadTestSources) compileTest = TOKEN;
        if (runTests == SKIP && hadTestSources) runTests = TOKEN;
        if (pkg == SKIP) pkg = TOKEN;
        if (sync == SKIP) sync = TOKEN;

        // A module whose every work step is only a token is fully cached: always-run tails
        // shrink to a touch rather than full static weight.
        boolean fullyCached = isTokenOrSkip(sync)
                && isTokenOrSkip(compileJava)
                && isTokenOrSkip(compileKotlin)
                && isTokenOrSkip(compileGroovy)
                && isTokenOrSkip(compileTest)
                && isTokenOrSkip(runTests)
                && isTokenOrSkip(pkg);
        return new Plan(sync, compileJava, compileKotlin, compileGroovy, compileTest, runTests, pkg, fullyCached);
    }

    /** True when weight is absent or only a token (no real compile/test/package work). */
    static boolean isTokenOrSkip(int weight) {
        return weight <= TOKEN;
    }

    /**
     * {@code ensure-jdk}: 70 only when a JDK download will actually happen — the same condition
     * {@link JdkEnsure} uses ({@code resolve} finds no usable JDK across the whole order, including
     * the current/PATH tiers, and a spec <em>would install</em>). {@code resolve} is offline; the
     * download it predicts is the network cost. Anything resolvable on disk → 1.
     */
    public static int jdkWeight(Path dir, Path jdksDir) {
        try {
            JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
            Lockfile lock = Files.exists(dir.resolve("jk.lock")) ? LockfileReader.read(dir.resolve("jk.lock")) : null;
            cc.jumpkick.jdk.JdkRegistry registry =
                    jdksDir != null ? new cc.jumpkick.jdk.JdkRegistry(jdksDir) : new cc.jumpkick.jdk.JdkRegistry();
            var req = new cc.jumpkick.jdk.JdkResolution.Request(
                    dir,
                    cc.jumpkick.config.SessionContext.current().jdkSpec(),
                    System.getenv("JK_JDK"),
                    lock != null ? lock.jdk() : null,
                    project.project() != null ? project.project().jdk() : null,
                    project.project() != null ? project.project().javaRelease() : 0,
                    System::getenv);
            var r = cc.jumpkick.jdk.JdkResolution.resolve(
                    req,
                    registry,
                    cc.jumpkick.jdk.GlobalDefaultJdk.current(),
                    cc.jumpkick.jdk.JdkLts.OFFLINE_LATEST_LTS);
            return (r.jdk().isEmpty() && r.wouldInstall()) ? JDK_DOWNLOAD : SKIP;
        } catch (Exception e) {
            return SKIP;
        }
    }

    /**
     * Assembly jar present and at least as new as the main jar (and not {@code --force}) → skip.
     */
    public static int assemblyWeight(Path dir) {
        return artifactFresh(dir, BuildLayout::assemblyJar)
                ? SKIP
                : learnedFixedWeight(dir.toString(), "package-assembly", ASSEMBLY_RUN);
    }

    /** Native binary/library present and fresh → skip; otherwise a full native-image build. */
    public static int nativeWeight(Path dir) {
        return artifactFresh(dir, BuildLayout::nativeBinary) || artifactFresh(dir, BuildLayout::nativeLibrary)
                ? SKIP
                : learnedFixedWeight(dir.toString(), "native-image", NATIVE_RUN);
    }

    /** OCI image tarball present and fresh → skip (2); otherwise a full image build (40). */
    public static int ociWeight(Path dir) {
        return artifactFresh(dir, BuildLayout::ociImageTar)
                ? OCI_SKIP
                : learnedFixedWeight(dir.toString(), "write-image", OCI_RUN);
    }

    /**
     * True when the artifact selected by {@code artifact} exists, isn't being forced by {@code
     * --force}, and is at least as new as the main jar it's derived from — a cheap "this output is
     * up-to-date" proxy for the artifact-cache skip the step itself performs.
     */
    private static boolean artifactFresh(Path dir, java.util.function.Function<BuildLayout, Path> artifact) {
        try {
            if (cc.jumpkick.config.SessionContext.current().config().rebuildOr(false)) return false;
            JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
            BuildLayout layout = BuildLayout.of(dir, project);
            Path art = artifact.apply(layout);
            if (!Files.isRegularFile(art)) return false;
            Path mainJar = layout.mainJar();
            if (!Files.isRegularFile(mainJar)) return true; // nothing to compare against
            return Files.getLastModifiedTime(art).toMillis()
                    >= Files.getLastModifiedTime(mainJar).toMillis();
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetch weight: 8 per artifact not already in the CAS (all of them under {@code  --force}). */
    private static int predictSync(BuildPipelines.Inputs in, Cas cas) {
        try {
            if (!Files.exists(in.lockFile())) return ARTIFACT_FETCH; // first run resolves+fetches
            Lockfile lock = LockfileReader.read(in.lockFile());
            int fetches = 0;
            for (Lockfile.Artifact a : lock.artifacts()) {
                String checksum = a.checksum();
                if (checksum == null) continue; // pom-only / path / git — nothing to fetch
                // Only artifacts missing from the CAS cost anything to sync. --force/--refresh does
                // NOT re-download blobs already present: the CAS is content-addressed (a stored
                // sha256 is byte-identical), so a forced build resolves entirely from local disk —
                // it even succeeds offline. Reserving a per-artifact download here for cached deps
                // was the bug that made `jk explain --force` predict tens of seconds of phantom fetch.
                String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
                if (!cas.contains(hex)) fetches++;
            }
            return fetches == 0 ? SKIP : fetches * ARTIFACT_FETCH;
        } catch (Exception e) {
            return SKIP;
        }
    }

    // --- parallel-aware wall-clock estimate ----------------------------------

    /**
     * One module's cost for the schedule estimate: its weight, its serialized test weight, and its
     * prereqs.
     */
    public record ModuleCost(Path dir, Set<Path> prereqs, int weight, int testWeight) {}

    /**
     * The {@link ModuleCost} of a prepared module pipeline: its total estimated bar weight, plus the
     * serialized {@code run-tests} slice pulled out on its own (the schedule estimate treats that
     * step as a cross-module serial bound). Shared by {@code jk build} and {@code jk explain} so
     * their wall-clock estimates are computed from the pipeline identically.
     */
    public static ModuleCost costOf(Path dir, Set<Path> prereqs, cc.jumpkick.run.Pipeline pipeline) {
        int weight = pipeline.estimatedTotalWeight();
        int testWeight = pipeline.steps().stream()
                .filter(p -> p.name().equals("run-tests"))
                .mapToInt(cc.jumpkick.run.Step::estimateWeight)
                .sum();
        return new ModuleCost(dir, prereqs, weight, testWeight);
    }

    /**
     * Module cost from pre-computed weights (JK-1114 shape memo / ETA-only path) — no pipeline
     * assembly. {@code testWeight} is the serial {@code run-tests} slice; 0 when unknown.
     */
    public static ModuleCost costOf(Path dir, Set<Path> prereqs, int weight, int testWeight) {
        return new ModuleCost(dir, prereqs, Math.max(0, weight), Math.max(0, testWeight));
    }

    /**
     * Estimate a build's wall-clock (ms) from per-module weights, honoring how {@code jk build}
     * actually schedules. Serial ({@code -j1}) sums every module's weight. The parallel
     * graph build overlaps independent modules, so the estimate is the largest of three lower bounds
     * — the dependency <b>critical path</b> (longest weighted chain, since a module can't start
     * before its prereqs finish), the <b>throughput</b> ceiling (Σweight ÷ concurrency, when work
     * saturates the worker JVMs), and — when tests are serialized across modules (the default, no
     * {@code --parallel-tests}) — the <b>serial test-step total</b>, since those steps share one
     * test JVM and cannot overlap. Summing everything (the old estimate) over-counts the
     * compile/package work that overlaps the long serial test tail.
     */
    public static long scheduleMillis(List<ModuleCost> mods, int concurrency, boolean serial, boolean parallelTests) {
        return scheduleMillis(mods, concurrency, serial, parallelTests, MS_PER_WEIGHT);
    }

    /**
     * As {@link #scheduleMillis(List, int, boolean, boolean)} but with an explicit weight→ms
     * conversion. Pass {@link #MS_PER_WEIGHT} on a warm machine (learned rates already encode this
     * host, so the constant round-trips exactly); pass a {@link Calibration}-measured or live
     * re-projected rate on a cold machine, where the constant is a blind guess. Applying a measured
     * rate only when the learned ledger is cold keeps it from stacking on top of learned rates.
     */
    public static long scheduleMillis(
            List<ModuleCost> mods, int concurrency, boolean serial, boolean parallelTests, long msPerWeight) {
        long serialSum = 0;
        long testSum = 0;
        for (ModuleCost m : mods) {
            serialSum += m.weight();
            testSum += m.testWeight();
        }
        if (serial || concurrency <= 1) return serialSum * msPerWeight;
        long critical = criticalPath(mods);
        long throughput = (serialSum + concurrency - 1) / concurrency;
        long testFloor = parallelTests ? 0 : testSum;
        return Math.max(critical, Math.max(throughput, testFloor)) * msPerWeight;
    }

    /**
     * As {@link #scheduleMillis(List, int, boolean, boolean, long)} but with a <em>per-module</em>
     * weight→ms rate. A single workspace-wide rate mis-priced the common mixed case — a brand-new
     * module beside already-built ones: the whole estimate had to pick one rail, so either the new
     * module was priced at {@link #MS_PER_WEIGHT} (the reference machine, ~4× hot on a fast host,
     * because its static reference-frame weights don't encode this host) or the built modules were
     * priced at the calibration rate (wrong for them — their learned rates already round-trip at 150).
     * Pricing each module by its own learned-ness fixes both at once: a warm module (learned rates for
     * its dir) converts at 150; a cold module converts at this host's measured calibration.
     *
     * <p>Implemented by pre-scaling each module's weights into ms-space with its own rate, then
     * running the identical schedule model at 1 ms/unit — so the critical-path / throughput /
     * serial-test bounds compose across modules that were priced differently.
     */
    public static long scheduleMillis(
            List<ModuleCost> mods,
            int concurrency,
            boolean serial,
            boolean parallelTests,
            java.util.function.ToDoubleFunction<Path> msPerWeightForModule) {
        List<ModuleCost> inMs = new ArrayList<>(mods.size());
        for (ModuleCost m : mods) {
            double r = msPerWeightForModule.applyAsDouble(m.dir());
            inMs.add(new ModuleCost(m.dir(), m.prereqs(), scaleToMs(m.weight(), r), scaleToMs(m.testWeight(), r)));
        }
        return scheduleMillis(inMs, concurrency, serial, parallelTests, 1L);
    }

    /** Weight × rate, rounded and clamped to a positive int (ms-space) so the schedule math can't overflow. */
    private static int scaleToMs(int weight, double rate) {
        long ms = Math.round(weight * Math.max(0.0, rate));
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, ms));
    }

    /**
     * Longest <em>blocking</em>-weighted path through the module DAG: {@code finish(m) = blocking(m)
     * + max prereq finish}. Only a module's blocking work (compile + package — what produces the jar
     * dependents wait on) sits on the path; its test step doesn't block dependents and is accounted
     * for separately by the serial test floor, so counting it here would double the tail.
     */
    private static long criticalPath(List<ModuleCost> mods) {
        java.util.Map<Path, ModuleCost> byDir = new java.util.HashMap<>();
        for (ModuleCost m : mods) byDir.put(m.dir(), m);
        java.util.Map<Path, Long> finish = new java.util.HashMap<>();
        long best = 0;
        for (ModuleCost m : mods) best = Math.max(best, pathFinish(m, byDir, finish));
        return best;
    }

    private static long pathFinish(
            ModuleCost m, java.util.Map<Path, ModuleCost> byDir, java.util.Map<Path, Long> memo) {
        Long cached = memo.get(m.dir());
        if (cached != null) return cached;
        long blocking = Math.max(0, m.weight() - m.testWeight());
        memo.put(m.dir(), blocking); // cycle guard
        long upstream = 0;
        for (Path p : m.prereqs()) {
            ModuleCost pm = byDir.get(p);
            if (pm != null) upstream = Math.max(upstream, pathFinish(pm, byDir, memo));
        }
        long f = blocking + upstream;
        memo.put(m.dir(), f);
        return f;
    }
}
