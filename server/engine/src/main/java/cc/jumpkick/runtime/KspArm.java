// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.BuildPlanner.*;
import static cc.jumpkick.runtime.PlannerKsp.*;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.KspProcessors;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.KotlinBtaResolver;
import cc.jumpkick.runtime.base.KspResolver;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.CompileWarnings;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.KeyOwner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One plan's KSP rounds: the {@code ksp} step with its body and predictor, the test round
 * compile-test runs before its compilers, and the one round runner both share. Built once per plan
 * from what its {@link BuildPlanner.Ctx} holds; the derivations the forecast reads as well (the
 * output dirs, the source roots, the stamp and the key) are {@link PlannerKsp}'s, and the
 * forecast's reading of a round is {@link KspForecast}.
 */
final class KspArm {

    private final BuildPlanner.Inputs in;
    private final Cas cas;
    private final ActionCache actionCache;
    private final boolean compact;
    private final boolean kotlinModule;
    private final @Nullable PluginDeclarations pluginDecls;

    KspArm(BuildPlanner.Ctx cx, @Nullable PluginDeclarations pluginDecls) {
        this.in = cx.in();
        this.cas = cx.cas();
        this.actionCache = cx.actionCache();
        this.compact = cx.compact();
        this.kotlinModule = cx.kotlinModule();
        this.pluginDecls = pluginDecls;
    }

    /**
     * KSP2 round: fork {@code KSPJvmMain} with KSP processor jars ({@link
     * cc.jumpkick.compile.KspProcessors}); outputs under {@code target/ksp/} join compile sources.
     */
    Task ksp() {
        // Plugin-contributed sources (protoc output, variant extra-src) must exist before the
        // round and join its source roots — a contributed @Module/@Entity is processor input
        // like any hand-written one.
        List<String> requires = new ArrayList<>(List.of(
                TaskNames.PARSE_BUILD,
                TaskNames.RESOLVE_DEPS,
                TaskNames.ENSURE_JDK,
                TaskNames.BUILD_LOGIC_BEFORE_COMPILE));
        requires.addAll(sourceGenStepSteps(pluginDecls));
        return Task.builder(TaskNames.KSP)
                .forecast(StepForecast.priced(this::forecastKsp))
                .stage(BuildStage.COMPILE)
                .label("KSP")
                .kind(TaskKind.CPU)
                .requires(requires.toArray(new String[0]))
                .ticks(1)
                .execute(this::runKsp)
                .build();
    }

    /**
     * The ksp step's price: it runs when the main round's stamp is stale and no record answers its
     * inputs (a processor bump, a source edit nothing has built); otherwise it rides with the
     * module, restoring or skipping without work worth pricing.
     */
    private TaskForecast.@Nullable Task forecastKsp(StepForecast.Scope scope) throws Exception {
        ForecastScope s = ForecastScope.of(scope);
        if (!s.prepared().mainRound().runs()) return null;
        return new TaskForecast.Task(TaskNames.KSP, TaskForecast.Status.RUN, "KSP round · inputs changed", null);
    }

    /** The main KSP2 round: split the processors, then run the round over the main sources. */
    private void runKsp(TaskContext ctx) throws Exception {
        ModuleClasspaths classpaths = ctx.require(CLASSPATHS);
        List<Path> processorCp = classpaths.processor();
        var split = KspProcessors.split(processorCp);
        BuildLayout layout = ctx.require(LAYOUT);
        if (split.ksp().isEmpty()) {
            // Processors that are all javac's leave nothing of an earlier round to read.
            PathUtil.deleteRecursively(kspOutBase(layout));
            CompileWarnings.writeCurrent(
                    CompileWarnings.currentFile(layout.buildDir().resolve("incremental"), TaskNames.KSP), List.of());
            ctx.label("no KSP processors");
            ctx.progress(1);
            return;
        }
        JkBuild project = ctx.require(PROJECT);
        Round round = new Round(
                kspOutBase(layout),
                TaskNames.KSP,
                kspSourceRoots(in, project, layout, pluginDecls, compact),
                mainRoundInputs(kotlinSources(ctx), javaSources(ctx), layout, pluginDecls),
                classpaths.main(),
                processorCp,
                split.ksp());
        if (runRound(ctx, round) == RoundOutcome.KEPT) {
            ctx.reweight(EffortWeights.TOKEN); // cache/stamp skip — token tick
            ctx.label("up to date");
        }
        ctx.progress(1);
    }

    /**
     * One KSP2 round's inputs. {@code outBase} receives {@code kotlin/}, {@code java/}, {@code
     * classes/} and {@code resources/}; {@code stampInputs} are the hand-written sources the round
     * reads; {@code processorCp} is the whole processor closure and {@code kspJars} the jars in it
     * that register a {@code SymbolProcessorProvider}.
     */
    record Round(
            Path outBase,
            String producer,
            List<Path> sourceRoots,
            List<Path> stampInputs,
            List<Path> classpath,
            List<Path> processorCp,
            List<Path> kspJars) {}

    /**
     * What became of a round's output: {@link #KEPT} on disk under a fresh stamp, {@link #REPLACED}
     * without running KSP (restored from the round's record, or removed with no round to refresh
     * it), or rewritten by a KSP run that {@link #RAN}.
     */
    enum RoundOutcome {
        KEPT,
        REPLACED,
        RAN
    }

    /**
     * Run {@code round} unless its stamp is fresh: resolve the toolchain, clear the previous
     * outputs, restore them from the action record the round's inputs name or fork KSP and record
     * what it wrote, surface its diagnostics and stamp.
     */
    @KeyOwner(
            step = TaskNames.KSP,
            unpaired = "the round writes its stamp; the forecast reads it through roundStampFresh",
            forecastsStep = true)
    RoundOutcome runRound(TaskContext ctx, Round round) throws Exception {
        Path outBase = round.outBase();
        List<Path> stampCp = stampClasspath(round.classpath(), round.kspJars());
        boolean rerun = in.session().config().rebuildOr(false);
        JkBuild project = ctx.require(PROJECT);
        String kotlinVersion = kotlinVersion(ctx.require(PARSED_BUILD).lockfile(), project);
        // Processor options and the toolchain are round inputs no file mtime reflects; the digest
        // is what makes a `ksp-options` edit with untouched sources re-run the round.
        String kspVersion = KspResolver.versionFor(project);
        String optionsDigest = roundDigest(
                project,
                ctx.require(PARSED_BUILD).lockfile(),
                in.dir(),
                ctx.require(JAVA_HOME),
                ctx.require(PARSED_BUILD).release());
        // The warnings the round's outputs were made with: a fresh stamp without them is not up to date.
        Path warnings = CompileWarnings.currentFile(
                ctx.require(LAYOUT).buildDir().resolve("incremental"), CompileWarnings.roundFile(round.producer()));
        Optional<List<CompileWarnings.Kept>> kept =
                rerun ? Optional.empty() : CompileWarnings.readCurrent(warnings, round.stampInputs());
        if (kept.isPresent()
                && roundStampFresh(
                        outBase,
                        round.stampInputs(),
                        round.classpath(),
                        round.kspJars(),
                        ctx.require(PARSED_BUILD).release(),
                        optionsDigest)) {
            for (CompileWarnings.Kept k : kept.get())
                ctx.replayedWarn(k.tool(), "", k.warning().describe());
            return RoundOutcome.KEPT;
        }

        // Before the round reads a source: a source edited while it runs is stale next time.
        long readClock = FreshnessStamp.clockNow(outBase);
        String key = roundKey(
                outBase,
                optionsDigest,
                round.stampInputs(),
                round.classpath(),
                round.processorCp(),
                ClasspathFingerprint.ON_DISK);
        // A stale round's outputs must not survive into the source union or the classes tree.
        for (String sub : OUTPUT_DIRS) {
            PathUtil.deleteRecursively(outBase.resolve(sub));
        }
        Optional<ActionCache.ActionRecord> recorded = rerun ? Optional.empty() : actionCache.lookup(key);
        RoundOutcome outcome = RoundOutcome.REPLACED;
        List<CompileWarnings.Kept> said = new ArrayList<>();
        if (recorded.isPresent() && actionCache.restore(recorded.get(), outBase)) {
            // The record is what the forecast projects the classes tree from after `jk clean`;
            // restoring it rather than re-running is what makes the two agree.
            ctx.label("cache hit");
            said.addAll(CompileWarnings.keptFromSaid(recorded.get().said()));
            for (CompileWarnings.Kept k : said)
                ctx.replayedWarn(k.tool(), "", k.warning().describe());
        } else {
            outcome = RoundOutcome.RAN;
            ctx.label("KSP " + kspVersion + ": " + round.kspJars().size() + " processor jar(s)");
            KspToolchain toolchain = resolveKspToolchain(project, cas, kotlinVersion, kspVersion);
            Files.createDirectories(outBase.resolve("caches"));
            List<Path> libs = new ArrayList<>(round.classpath());
            libs.add(toolchain.stdlib());
            List<String> cmd = kspCommand(
                    ctx,
                    in,
                    project,
                    outBase,
                    toolchain.kspClasspath(),
                    round.sourceRoots(),
                    libs,
                    kotlinVersion,
                    round.processorCp());
            WorkerEnv kspEnv = WorkerEnv.forModule(
                            project.build().env(), in.dir(), BuildLayout.moduleTargetDir(in.dir()))
                    .withJavaHome(JavaHomes.runningJavaHome());
            String output = runKspProcess(ctx, in.dir(), kspEnv, cmd);
            // A green round still has things to say. Processor `logger.warn`/`info` is how
            // an annotation-driven framework explains what it did and what to do
            // differently; dropping it on success meant guidance only ever appeared once
            // the build was already broken. Surfaced the same way javac
            // diagnostics are, so -q/-v behave consistently.
            for (BuildPlanner.KspDiagnostic diagnostic : kspDiagnostics(output)) {
                ctx.warn(diagnostic.severity(), diagnostic.message());
                said.add(new CompileWarnings.Kept(
                        diagnostic.severity(),
                        new CompileResult.Diagnostic(
                                CompileResult.Severity.WARNING, null, 0, 0, diagnostic.message())));
            }
            // The round is not incremental: every run starts from cleared outputs, so its caches
            // hold nothing a later round reads, and the record is the four output dirs alone.
            PathUtil.deleteRecursively(outBase.resolve("caches"));
            if (!in.ephemeralActions()) record(actionCache, key, outBase, CompileWarnings.keptSaid(said));
        }
        CompileWarnings.writeCurrent(warnings, said);
        FreshnessStamp.write(
                outBase,
                BuildStamps.KSP,
                round.producer(),
                key,
                round.stampInputs(),
                stampCp,
                ctx.require(PARSED_BUILD).release(),
                optionsDigest,
                readClock);
        return outcome;
    }

    /** Record what a round wrote under {@code outBase}, an empty round as the verdict that it wrote nothing. */
    private static void record(ActionCache actionCache, String key, Path outBase, List<String> said)
            throws IOException {
        String taskId = roundTaskId(outBase);
        Map<String, String> inputs = Map.of(TaskNames.KSP, key);
        boolean wroteAny = false;
        for (String sub : OUTPUT_DIRS) {
            wroteAny |= PathUtil.anyRegularFile(outBase.resolve(sub), d -> false, f -> true);
        }
        if (wroteAny) {
            actionCache.store(taskId, key, inputs, outBase, said);
        } else {
            actionCache.storeVerdict(taskId, key, inputs, said);
        }
    }

    /**
     * The test round, run by compile-test before the test compilers in a Kotlin module with a KSP
     * processor in either table: the processors over the selected suites' hand-written sources,
     * less {@code [test] exclude-src}, into {@code target/ksp-test}. {@code classpath} is the test
     * compile classpath, so the main classes (and the main round's output already mirrored into
     * them) are what the processors resolve against. The processors are the shared table's
     * closure plus what {@code [test-processor-dependencies]} adds (that table's alone under its
     * {@code inherit = false}), so a module whose only KSP processor is a test one still gets its
     * round. Its generated sources join {@link
     * TestSources}; its bytecode and resources are mirrored into the test classes with
     * the test resources. A module with no KSP processor keeps no test round output.
     */
    RoundOutcome runTestRound(TaskContext ctx, TestSources src, List<Path> classpath, List<String> compiledSuites)
            throws Exception {
        BuildLayout layout = ctx.require(LAYOUT);
        if (!kotlinModule) return dropped(dropTestRound(layout));
        Path outBase = kspTestOutBase(layout);
        List<Path> processorCp = ctx.require(CLASSPATHS).kspTestProcessor();
        List<Path> kspJars = KspProcessors.split(processorCp).ksp();
        List<Path> roots =
                testSourceRoots(ctx.require(PROJECT), in.dir(), compact, compiledSuites, layout, pluginDecls);
        if (kspJars.isEmpty() || roots.isEmpty()) return dropped(dropTestRound(layout));
        List<Path> handWritten = new ArrayList<>();
        for (Path source : src.all()) {
            if (!source.startsWith(outBase)) handWritten.add(source);
        }
        return runRound(
                ctx,
                new Round(
                        outBase,
                        TaskNames.COMPILE_TEST,
                        keptSourceRoots(roots, handWritten),
                        handWritten,
                        classpath,
                        processorCp,
                        kspJars));
    }

    private static RoundOutcome dropped(boolean removed) {
        return removed ? RoundOutcome.REPLACED : RoundOutcome.KEPT;
    }

    /** The KSP2 runtime classpath and the Kotlin stdlib the round compiles against. */
    private record KspToolchain(List<Path> kspClasspath, Path stdlib) {}

    private static KspToolchain resolveKspToolchain(JkBuild project, Cas cas, String kotlinVersion, String kspVersion)
            throws Exception {
        try {
            RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
            List<Path> kspClasspath = KspResolver.resolveClasspath(repos, cas, kspVersion);
            Path stdlib = KotlinBtaResolver.resolveStdlib(repos, cas, kotlinVersion);
            return new KspToolchain(kspClasspath, stdlib);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted resolving KSP", e);
        }
    }
}
