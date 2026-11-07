// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.KspProcessors;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.KspResolver;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.ProjectedFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The forecast's reading of a module's KSP rounds, beside {@link PlannerKsp}: whether each round
 * runs at all, and what it leaves in its output dirs by the time the compilers read them. The
 * build decides a round by its freshness stamp, then by the action record its inputs name, then
 * runs it; the forecast asks the same questions of the same inputs, the processor path digest
 * among them, so after {@code jk clean}, a stale stamp or a processor bump it reads the files the
 * build will compile and mirror rather than what {@code target/} holds now.
 */
final class KspForecast {

    private KspForecast() {}

    /** How the build will come by a round's output. */
    enum State {
        /** No round runs: the build removes its output dirs. */
        NONE,
        /** The stamp holds: the output on disk is what the build reads. */
        FRESH,
        /** The stamp is stale and the round's inputs name a record: the build restores it. */
        RECORDED,
        /** The stamp is stale and no record answers: the round runs, and what it writes is unknown. */
        RUNS
    }

    /** One round's verdict and, when a record answers its inputs, that record's outputs. */
    record Round(State state, Path outBase, @Nullable Map<String, String> outputs) {

        static Round none(Path outBase) {
            return new Round(State.NONE, outBase, null);
        }

        boolean runs() {
            return state == State.RUNS;
        }

        /**
         * What the round places in the classes tree: empty when no round runs, its record's when
         * one answers, null when what it writes cannot be described.
         */
        @Nullable
        Map<String, String> classesTree() {
            if (state == State.NONE) return Map.of();
            return outputs == null ? null : PlannerKsp.classesTreeOutputs(outputs);
        }

        /**
         * Whether the build rewrites any of {@code sources} through this round before a compiler
         * reads them — restored from its record or written by a run — so a stamp over what is on
         * disk now answers for files about to change.
         */
        boolean rewrites(List<Path> sources) {
            if (state != State.RECORDED && state != State.RUNS) return false;
            Path base = outBase.toAbsolutePath().normalize();
            for (Path source : sources) {
                if (source.toAbsolutePath().normalize().startsWith(base)) return true;
            }
            return false;
        }

        /**
         * Lay the round's output dirs into {@code projection} as the build leaves them: a record's
         * files, or nothing at all for a round that does not run. A fresh round's dirs are read
         * from disk, and a running round's are unknown, so neither is projected.
         */
        void projectInto(ProjectedFiles projection) {
            if (state == State.FRESH || state == State.RUNS) return;
            for (String sub : PlannerKsp.OUTPUT_DIRS) {
                Map<String, String> files = new TreeMap<>();
                if (outputs != null) {
                    String prefix = sub + "/";
                    for (Map.Entry<String, String> e : outputs.entrySet()) {
                        if (e.getKey().startsWith(prefix))
                            files.put(e.getKey().substring(prefix.length()), e.getValue());
                    }
                }
                projection.project(outBase.resolve(sub), files);
            }
        }
    }

    /** The options, sources and paths one round is decided by, as the build's {@code runRound} reads them. */
    record Inputs(
            JkBuild project,
            Path dir,
            Lockfile lock,
            Path javaHome,
            int release,
            ActionCache actionCache,
            ClasspathFingerprint.EntryIdentity identity) {}

    /**
     * The main round: none unless the module runs one with a KSP processor among its processors,
     * else decided over the hand-written {@code roundInputs} against the main compile {@code
     * classpath} the build hands it.
     */
    static Round mainRound(
            Inputs in, BuildLayout layout, List<Path> roundInputs, List<Path> classpath, List<Path> processorCp)
            throws IOException {
        Path outBase = PlannerKsp.kspOutBase(layout);
        List<Path> kspJars = KspProcessors.split(processorCp).ksp();
        if (!PlannerKsp.runsMainRound(in.project(), in.dir()) || kspJars.isEmpty()) return Round.none(outBase);
        return decide(in, outBase, roundInputs, classpath, processorCp, kspJars);
    }

    /**
     * The test round compile-test runs: none outside a Kotlin module, without a KSP processor in
     * the test processor path or without a test source root, else decided over the suites'
     * {@code handWritten} sources against the test compile {@code classpath}.
     */
    static Round testRound(
            Inputs in,
            BuildLayout layout,
            boolean kotlinModule,
            boolean compact,
            List<String> compiledSuites,
            @Nullable PluginDeclarations decls,
            List<Path> handWritten,
            List<Path> classpath,
            List<Path> testProcessorCp)
            throws IOException {
        Path outBase = PlannerKsp.kspTestOutBase(layout);
        if (!kotlinModule) return Round.none(outBase);
        List<Path> kspJars = KspProcessors.split(testProcessorCp).ksp();
        List<Path> roots = PlannerKsp.testSourceRoots(in.project(), in.dir(), compact, compiledSuites, layout, decls);
        if (kspJars.isEmpty() || roots.isEmpty()) return Round.none(outBase);
        List<Path> inputs = new ArrayList<>();
        for (Path source : handWritten) {
            if (!source.startsWith(outBase)) inputs.add(source);
        }
        return decide(in, outBase, inputs, classpath, testProcessorCp, kspJars);
    }

    private static Round decide(
            Inputs in,
            Path outBase,
            List<Path> stampInputs,
            List<Path> classpath,
            List<Path> processorCp,
            List<Path> kspJars)
            throws IOException {
        String digest = PlannerKsp.kspStampDigest(
                in.project(),
                in.lock(),
                in.dir(),
                PlannerKsp.kotlinVersion(in.lock(), in.project()),
                KspResolver.versionFor(in.project()),
                in.javaHome(),
                in.release());
        List<Path> stampCp = new ArrayList<>(classpath);
        stampCp.addAll(kspJars);
        boolean fresh = FreshnessStamp.isFresh(outBase, BuildStamps.KSP, stampInputs, stampCp, in.release(), digest);
        // The processor path is in the key by content: a processor bump names another record.
        String key = PlannerKsp.roundKey(outBase, digest, stampInputs, classpath, processorCp, in.identity());
        Map<String, String> outputs = in.actionCache()
                .lookup(key)
                .map(ActionCache.ActionRecord::outputs)
                .orElse(null);
        State state = fresh ? State.FRESH : outputs != null ? State.RECORDED : State.RUNS;
        return new Round(state, outBase, outputs);
    }

    /**
     * The ksp step's price: it runs when the main round's stamp is stale and no record answers its
     * inputs (a processor bump, a source edit nothing has built); otherwise it rides with the
     * module, restoring or skipping without work worth pricing.
     */
    static TaskForecast.@Nullable Task predict(ForecastScope s) throws Exception {
        if (!s.prepared().mainRound().runs()) return null;
        return new TaskForecast.Task(TaskNames.KSP, TaskForecast.Status.RUN, "KSP round · inputs changed", null);
    }
}
