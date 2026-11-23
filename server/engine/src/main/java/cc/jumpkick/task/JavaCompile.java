// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.ForkedJavac;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Action-cache front for Java compile. The {@code jk-java-compiler} worker owns Zinc incremental
 * recompile; this only does a whole-input action-key hit/miss around the fork.
 *
 * <p>The key hashes the sources before the worker reads them. A source whose bytes move while the
 * worker runs is re-read afterwards and named in {@link Result#movedSources}: the classes on disk
 * may come from either version, so no record is stored under the key and the Zinc analysis is
 * dropped, and the next build compiles the module from what is then on disk.
 */
public final class JavaCompile {

    private JavaCompile() {}

    /**
     * {@link #key(String, CompileRequest, String, List)} with the compile classpath read through
     * {@code cp}: the build's ABI tokens, or the forecast's view of a sibling tree the build
     * restores first.
     */
    public static String key(String taskId, CompileRequest request, String jkVersion, ActionKey.EntryToken cp)
            throws IOException {
        return key(taskId, request, jkVersion, ActionKey.javacClasspathTokens(request, cp));
    }

    /**
     * A javac step's action key: what {@link #run} looks up and records, what the plan's preflight
     * reweighs by, and what {@link #predict} prices, over the classpath lines {@link
     * ActionKey#javacClasspathTokens} reads. A step that keys its freshness stamp on those lines
     * reads its classpath once and hands the same list to the key and to {@link #run}.
     */
    @KeyOwner(
            step = {
                TaskNames.COMPILE_JAVA,
                TaskNames.COMPILE_TEST,
                TaskNames.COMPILE_TEST_FIXTURES,
                TaskNames.COMPILE_GUARD,
                TaskNames.COMPILE_VERSIONS
            })
    public static String key(String taskId, CompileRequest request, String jkVersion, List<String> classpathTokens)
            throws IOException {
        return ActionKey.forJavac(taskId, request, jkVersion, classpathTokens);
    }

    /** {@link #key(String, CompileRequest, String, List)} over the build's classpath tokens. */
    public static String key(String taskId, CompileRequest request, String jkVersion) throws IOException {
        return key(taskId, request, jkVersion, ActionKey.javacClasspathTokens(request));
    }

    /** Test seam: runs once the key is taken and before the worker is forked. */
    static volatile Runnable beforeFork = () -> {};

    /**
     * @param diagnostics what the compiler said in this run
     * @param replayed the warnings of sources this run did not compile, as their last compile said
     *     them: every warning of a cache hit, the untouched sources' of an incremental compile
     * @param movedSources sources whose bytes changed between the key and the worker's return;
     *     the compile is not recorded and the next build recompiles them
     * @param waitMillis time spent queued behind the shared compiler worker; see TaskContext#waited
     * @param missReason why the compile ran: the compiler's reason when its prior analysis could
     *     not carry the compile ({@code annotation processors changed}), else what moved since the
     *     step's last record ({@link ActionKey#missReason}); empty on a hit, with no prior record,
     *     or when nothing it names moved
     */
    public record Result(
            boolean success,
            String outcome,
            String actionKey,
            List<CompileResult.Diagnostic> diagnostics,
            List<CompileResult.Diagnostic> replayed,
            List<Path> compiledSources,
            List<Path> movedSources,
            long waitMillis,
            String missReason) {

        public Result(
                boolean success,
                String outcome,
                String actionKey,
                List<CompileResult.Diagnostic> diagnostics,
                List<CompileResult.Diagnostic> replayed,
                List<Path> compiledSources,
                List<Path> movedSources,
                long waitMillis) {
            this(success, outcome, actionKey, diagnostics, replayed, compiledSources, movedSources, waitMillis, "");
        }

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            replayed = replayed == null ? List.of() : List.copyOf(replayed);
            compiledSources = compiledSources == null ? List.of() : List.copyOf(compiledSources);
            movedSources = movedSources == null ? List.of() : List.copyOf(movedSources);
        }

        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }

        /** Every warning the classes this run left on disk were compiled with. */
        public List<CompileResult.Diagnostic> warnings() {
            List<CompileResult.Diagnostic> out = new ArrayList<>(replayed);
            out.addAll(CompileWarnings.replayable(diagnostics, false));
            out.sort(CompileWarnings.ORDER);
            return out;
        }
    }

    /**
     * Which compiler workers a forecast may ask for Zinc's plan of a compile, chosen by who else is
     * on them: a plan queues behind the compiles a worker is running. The rule shapes the forecast's
     * compile verdicts and never how the ETA prices them.
     */
    public enum PlanWorkers {
        /** Any, starting one if none runs: {@code jk explain}, whose answer is the plan. */
        ANY,
        /**
         * Only one already running ({@code jk status}): a cold worker's start costs seconds, so
         * otherwise the compile is predicted from the prior record.
         */
        WARM,
        /**
         * None: the prior record only. The ETA priced beside a running build, whose plan requests
         * would queue on the very lanes compiling the build's modules.
         */
        NONE
    }

    /** Bound by a forecast that limits which workers it plans on; unbound is {@link PlanWorkers#ANY}. */
    public static final ScopedValue<PlanWorkers> PLAN_WORKERS = ScopedValue.newInstance();

    static {
        ContextPropagator.carry(PLAN_WORKERS);
    }

    public enum Outcome {
        CACHE_HIT,
        INCREMENTAL,
        FULL
    }

    public record Prediction(Outcome outcome, String actionKey, int sourceCount, String reason, List<Path> sources) {
        public Prediction {
            reason = reason == null ? "" : reason;
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        public Prediction(Outcome outcome, String actionKey, int sourceCount, String reason) {
            this(outcome, actionKey, sourceCount, reason, List.of());
        }

        public Prediction(Outcome outcome, String actionKey, int sourceCount) {
            this(outcome, actionKey, sourceCount, "");
        }
    }

    /** {@code label} names the module and step in a worker failure; see {@link ForkedJavac.Request#label()}. */
    public static Result run(
            String taskId,
            String label,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env)
            throws IOException {
        return run(
                taskId,
                label,
                request,
                jkVersion,
                useCache,
                true,
                cas,
                actionCache,
                stateDir,
                workerJar,
                generatedSourceDir,
                env);
    }

    public static Result run(
            String taskId,
            String label,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env)
            throws IOException {
        List<String> classpathTokens =
                request.sources().isEmpty() ? List.of() : ActionKey.javacClasspathTokens(request);
        return run(
                taskId,
                label,
                request,
                jkVersion,
                useCache,
                persist,
                cas,
                actionCache,
                stateDir,
                workerJar,
                generatedSourceDir,
                env,
                classpathTokens);
    }

    /**
     * As above, keyed on {@code classpathTokens}: the lines {@link ActionKey#javacClasspathTokens}
     * reads of {@code request}, which a step that stamps its compile has read already.
     */
    public static Result run(
            String taskId,
            String label,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env,
            List<String> classpathTokens)
            throws IOException {
        return run(
                taskId,
                label,
                request,
                jkVersion,
                useCache,
                persist,
                cas,
                actionCache,
                stateDir,
                workerJar,
                generatedSourceDir,
                env,
                classpathTokens,
                holds -> {});
    }

    /**
     * As above, telling {@code onLookup} whether the action cache holds the compile's key once it
     * has looked, before it restores or compiles; not told when {@code useCache} is false.
     */
    public static Result run(
            String taskId,
            String label,
            CompileRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env,
            List<String> classpathTokens,
            Consumer<Boolean> onLookup)
            throws IOException {
        Path out = Objects.requireNonNull(request.outputDir(), "outputDir");
        Files.createDirectories(out);
        if (request.sources().isEmpty()) {
            return new Result(true, "no-sources", "", List.of(), List.of(), List.of(), List.of(), 0L);
        }

        String key = key(taskId, request, jkVersion, classpathTokens);
        audit(taskId, request, jkVersion, key);
        if (useCache) {
            Result restored = restored(key, request, actionCache, stateDir, onLookup);
            if (restored != null) return restored;
        }

        Path gen = generatedSourceDir != null ? generatedSourceDir : stateDir.resolve("gen");
        Files.createDirectories(gen);
        if (!useCache) {
            IncrementalStateHygiene.redo(stateDir, () -> deleteClasses(out));
        } else {
            IncrementalStateHygiene.dropUnvouched(stateDir, out, () -> false);
        }
        Files.createDirectories(stateDir);
        ActionCachePrune.markInUse(stateDir);

        // The record's inputs describe the bytes the key hashed, so they are taken here, before
        // the worker reads a source; the same snapshot is what the post-fork re-read is held to.
        Map<String, String> inputs = ActionKey.snapshotInputs(request);
        inputs.put(ActionKey.JK_INPUT, jkVersion);
        String missReason = useCache ? missReason(actionCache, taskId, stateDir, inputs) : "";
        beforeFork.run();
        CasPrewriter prewriter = CasPrewriter.watching(cas, out);
        ForkedJavac.Result wr;
        try {
            wr = ForkedJavac.compile(new ForkedJavac.Request(
                            request.compilerHome(),
                            Objects.requireNonNull(workerJar, "workerJar"),
                            request.sources(),
                            request.classpath(),
                            request.processorPath(),
                            out,
                            gen,
                            request.release(),
                            request.extraOptions(),
                            stateDir,
                            request.scalaVersion(),
                            request.compilerClasspath(),
                            request.scalaLibraryJar(),
                            request.scalaCompilerJar(),
                            request.scalaBridgeJar(),
                            env)
                    .withClasspathAnalyses(producerAnalyses(request, stateDir))
                    .withLabel(label));
        } catch (RuntimeException | Error compileFailure) {
            // The failure is the result. finish() walks and hashes the output tree, and a walk
            // over what a dying compiler left behind can throw too — from a finally block that
            // throw would replace the compiler's own exception with a filesystem one.
            prewriter.close();
            throw compileFailure;
        }
        if (!wr.success()) {
            // Nothing records a failed compile's tree, so it is neither walked nor copied into the CAS.
            prewriter.close();
            return new Result(
                    false,
                    "errors",
                    key,
                    wr.diagnostics(),
                    List.of(),
                    wr.compiledSources(),
                    List.of(),
                    wr.waitMillis());
        }
        Map<String, String> outputs = MirroredOutputs.without(out, prewriter.finish());
        if (outputs.isEmpty() && !compiledEverySource(request, wr.compiledSources())) {
            // A compiler convinced nothing changed (stale incremental state) can report success
            // over an empty tree: nothing is recorded, and the ledger no longer matches the
            // analysis the worker wrote. A compile of every source that wrote no class file (a
            // package-info.java of javadoc alone) is recorded below like any other: no output is
            // its whole result, and its key must hit for the build and the forecast alike.
            IncrementalStateHygiene.distrust(stateDir);
            return new Result(
                    true,
                    "compiled-no-outputs",
                    key,
                    wr.diagnostics(),
                    List.of(),
                    wr.compiledSources(),
                    List.of(),
                    wr.waitMillis());
        }
        CompileWarnings.Merged warnings = CompileWarnings.merge(
                CompileWarnings.readLedger(stateDir, request.sources()),
                wr.compiledSources(),
                request.sources(),
                CompileWarnings.replayable(wr.diagnostics(), false));
        List<Path> moved = ActionKey.changedSources(request.sources(), inputs);
        if (!moved.isEmpty()) {
            // Neither version of the moved source is what the key names, and the analysis may
            // pair its earlier hash with classes from its later bytes: a revert to those earlier
            // bytes would then compile nothing. Nothing is recorded and the analysis goes, so the
            // next build compiles the module whole from what is on disk.
            IncrementalStateHygiene.forget(stateDir);
            WorkCounters.compiled(wr.compiledSources().size());
            return new Result(
                    true,
                    "compiled",
                    key,
                    wr.diagnostics(),
                    warnings.kept(),
                    wr.compiledSources(),
                    moved,
                    wr.waitMillis());
        }
        if (persist) {
            Files.createDirectories(stateDir);
            CompileWarnings.writeLedger(stateDir, warnings.all());
            actionCache.storeWithOutputs(taskId, key, inputs, outputs, CompileWarnings.said(warnings.all()));
            storeReferences(actionCache, cas, taskId, key, out);
            // The state's ledger names the compile that produced it, so the forecast reads this
            // checkout's own last compile rather than the shared pointer's.
            Files.createDirectories(stateDir);
            LangCompile.recordTree(stateDir, key, outputs);
            keepGeneratedLedger(stateDir, key, out);
        } else if (Files.isDirectory(stateDir)) {
            PathUtil.deleteRecursively(stateDir);
        }
        WorkCounters.compiled(wr.compiledSources().size());
        return new Result(
                true,
                "compiled",
                key,
                wr.diagnostics(),
                warnings.kept(),
                wr.compiledSources(),
                List.of(),
                wr.waitMillis(),
                // A whole compile is named by its compiler's reason, as the forecast names it: the
                // key's diff would say only the source that moved.
                wr.fullReason().isEmpty() ? missReason : wr.fullReason());
    }

    /**
     * What moved since the record this checkout's last compile of {@code taskId} left, against the
     * inputs this compile keys on; empty without such a record. Read before the compile stores its
     * own.
     */
    static String missReason(ActionCache actionCache, String taskId, Path stateDir, Map<String, String> inputs) {
        try {
            return actionCache
                    .lastFor(taskId, stateDir)
                    .map(prior -> ActionKey.missReason(prior.inputs(), inputs))
                    .orElse("");
        } catch (IOException unreadable) {
            return "";
        }
    }

    /**
     * The compile used {@code key}: an audited build ({@link KeyAudit}) takes it again once it has
     * ended, naming the sources and classpath entries as the record's inputs name them.
     */
    private static void audit(String taskId, CompileRequest request, String jkVersion, String key) {
        Path out = Objects.requireNonNull(request.outputDir(), "outputDir");
        KeyAudit.usedNaming(
                KeyAudit.moduleOf(out),
                KeyAudit.stepOf(taskId),
                key,
                () -> new KeyAudit.Keyed(
                        key(taskId, request, jkVersion, ActionKey.javacClasspathTokens(request)),
                        ActionKey.snapshotInputs(request)));
    }

    /**
     * The compile keyed {@code key} laid back from its record, saying again the warnings its run
     * said; null when there is no record or a blob of it is gone, and the compile runs. Tells
     * {@code onLookup} whether the record is there.
     */
    private static @Nullable Result restored(
            String key, CompileRequest request, ActionCache actionCache, Path stateDir, Consumer<Boolean> onLookup)
            throws IOException {
        Path out = Objects.requireNonNull(request.outputDir(), "outputDir");
        Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
        onLookup.accept(hit.isPresent());
        if (hit.isEmpty()) return null;
        ActionCache.ActionRecord cached = MirroredOutputs.omitting(out, hit.get());
        if (!actionCache.restore(cached, out)) return null;
        restoreReferences(actionCache, key, out);
        restoreGeneratedLedger(stateDir, key, out);
        Files.createDirectories(stateDir);
        LangCompile.recordTree(stateDir, key, cached.outputs());
        List<CompileResult.Diagnostic> said = CompileWarnings.fromSaid(cached.said(), request.sources());
        return new Result(true, "cache-hit:" + key.substring(0, 8), key, List.of(), said, List.of(), List.of(), 0L);
    }

    /**
     * Where jk's javac writes the reference index of {@code out}'s sources, beside the facts index:
     * {@code target/incremental/classes-refs} for {@code target/classes}. The Java compiler plugin's
     * {@code References.dirFor} writes there; this is the same rule, read from the engine side.
     */
    static Path referencesDir(Path out) {
        Path classes = out.toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(classes.getParent(), "a class output has a parent");
        return target.resolve("incremental").resolve(classes.getFileName() + "-refs");
    }

    /**
     * Where the Java compiler plugin keeps the ledger of the files processors generated into {@code
     * out}: {@code target/incremental/classes-generated.tsv} for {@code target/classes}, the rule
     * its {@code GeneratedProvenance.ledgerFor} writes by.
     */
    static Path generatedLedger(Path out) {
        Path classes = out.toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(classes.getParent(), "a class output has a parent");
        return target.resolve("incremental").resolve(classes.getFileName() + "-generated.tsv");
    }

    /** The copy of the generated-file ledger the compile state keeps: its compile's key, then the ledger. */
    static final String GENERATED_LEDGER = "generated";

    /**
     * Keep in {@code stateDir} the generated-file ledger the compile keyed {@code key} wrote beside
     * {@code out}. {@code jk clean} takes the ledger and not the state, so a hit of the same key
     * can lay it back ({@link #restoreGeneratedLedger}); the ledger names this checkout's absolute
     * paths, so it stays here and never enters the shared record. Best-effort.
     */
    static void keepGeneratedLedger(Path stateDir, String key, Path out) {
        Path kept = stateDir.resolve(GENERATED_LEDGER);
        try {
            Path ledger = generatedLedger(out);
            if (Files.isRegularFile(ledger)) {
                AtomicWrites.replace(kept, key + "\n" + Files.readString(ledger, StandardCharsets.UTF_8));
            } else {
                Files.deleteIfExists(kept);
            }
        } catch (IOException | RuntimeException unkept) {
            Log.debug("generated-file ledger not kept for " + out, unkept);
        }
    }

    /**
     * Make the generated-file ledger beside {@code out} the one the compile keyed {@code key} wrote,
     * or take it away when {@code stateDir} kept none for that key. The restored classes are that
     * compile's, so only its ledger vouches for their generated ones and keeps the next edit
     * incremental; a ledger left by another compile would name generated files these classes lack
     * and pass over the ones they have, and with none the next compile is whole. Best-effort, but
     * a ledger that cannot be laid back is deleted.
     */
    static void restoreGeneratedLedger(Path stateDir, String key, Path out) {
        Path ledger = generatedLedger(out);
        try {
            Path kept = stateDir.resolve(GENERATED_LEDGER);
            String text = Files.isRegularFile(kept) ? Files.readString(kept, StandardCharsets.UTF_8) : "";
            int nl = text.indexOf('\n');
            if (nl < 0 || !text.substring(0, nl).equals(key)) {
                Files.deleteIfExists(ledger);
                return;
            }
            String body = text.substring(nl + 1);
            if (Files.isRegularFile(ledger)
                    && Files.readString(ledger, StandardCharsets.UTF_8).equals(body)) return;
            Files.createDirectories(ledger.getParent());
            AtomicWrites.replace(ledger, body);
        } catch (IOException | RuntimeException unrestored) {
            Log.debug("generated-file ledger not restored for " + out, unrestored);
            try {
                Files.deleteIfExists(ledger);
            } catch (IOException undeleted) {
                Log.debug("generated-file ledger not deleted for " + out, undeleted);
            }
        }
    }

    /**
     * The key the reference index of the compile keyed {@code key} is cached under: hex like every
     * action key, since it names a file in the cache (a {@code :} is illegal there on Windows).
     */
    static String referencesKey(String key) {
        // Through the key hash: the compile key may hold a sealed value, and its shared form goes with it.
        return EnvKeyText.digest(key + "\nreferences");
    }

    /** The task the reference index of {@code taskId}'s compile is cached under: a file name on every OS. */
    static String referencesTaskId(String taskId) {
        return taskId + "+references";
    }

    /**
     * Caches the reference index beside the classes the compile keyed {@code key} wrote: it lives
     * outside the class output, so without this a compile served from the cache left the index
     * describing whatever javac last compiled, and a rename lost its exact spans exactly when the
     * build was fastest. Best-effort: an index that is not cached is one a reader re-derives or
     * passes over, never a failed build.
     */
    static void storeReferences(ActionCache cache, Cas cas, String taskId, String key, Path out) {
        Path dir = referencesDir(out);
        if (!Files.isDirectory(dir)) return;
        try {
            Map<String, String> files = new TreeMap<>();
            PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                if (!file.getFileName().toString().endsWith(".refs")) return;
                String hex = FileHashMemo.contentHash(file, attrs);
                if (!cas.contains(hex)) cas.putFile(file, hex);
                files.put(dir.relativize(file).toString().replace('\\', '/'), hex);
            });
            if (!files.isEmpty()) cache.storeWithOutputs(referencesTaskId(taskId), referencesKey(key), Map.of(), files);
        } catch (IOException | RuntimeException unstored) {
            Log.debug("reference index not cached for " + out, unstored);
        }
    }

    /** Lays back the reference index cached with the compile keyed {@code key}, when there is one. */
    static void restoreReferences(ActionCache cache, String key, Path out) {
        try {
            Optional<ActionCache.ActionRecord> refs = cache.lookup(referencesKey(key));
            if (refs.isPresent()) cache.restore(refs.get(), referencesDir(out));
        } catch (IOException | RuntimeException unrestored) {
            Log.debug("reference index not restored for " + out, unrestored);
        }
    }

    public static Prediction predict(
            String taskId, CompileRequest request, String jkVersion, ActionCache actionCache, Path stateDir)
            throws IOException {
        // No worker jar, so no plan worker is forked; the env is never consulted.
        return predict(taskId, request, jkVersion, actionCache, stateDir, null, null, WorkerEnv.strict());
    }

    public static Prediction predict(
            String taskId,
            CompileRequest request,
            String jkVersion,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            WorkerEnv env)
            throws IOException {
        return predict(taskId, request, jkVersion, actionCache, stateDir, workerJar, null, env);
    }

    /**
     * Zinc's own plan from a compiler worker for {@code env} (the build's, so the plan shares its
     * pool), as far as {@link #PLAN_WORKERS} allows, else the prior compile record.
     */
    public static Prediction predict(
            String taskId,
            CompileRequest request,
            String jkVersion,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env)
            throws IOException {
        return predict(
                taskId,
                request,
                jkVersion,
                actionCache,
                stateDir,
                workerJar,
                generatedSourceDir,
                env,
                ClasspathAbi::token);
    }

    /**
     * As above, with the compile classpath keyed through {@code cp}: the forecast's view of a
     * sibling tree the build restores before this compile runs.
     */
    public static Prediction predict(
            String taskId,
            CompileRequest request,
            String jkVersion,
            ActionCache actionCache,
            Path stateDir,
            @Nullable Path workerJar,
            @Nullable Path generatedSourceDir,
            WorkerEnv env,
            ActionKey.EntryToken cp)
            throws IOException {
        String key = key(taskId, request, jkVersion, cp);
        // A hit only when the restore would succeed: a record whose payload was pruned compiles.
        if (request.sources().isEmpty() || actionCache.restorable(key).isPresent()) {
            return new Prediction(Outcome.CACHE_HIT, key, request.sources().size(), "");
        }
        if (!Files.isRegularFile(stateDir.resolve(ProducerAnalyses.ANALYSIS_FILE))) {
            return new Prediction(Outcome.FULL, key, request.sources().size(), "no zinc analysis");
        }
        PlanWorkers planWorkers = Objects.requireNonNullElse(ContextPropagator.get(PLAN_WORKERS), PlanWorkers.ANY);
        if (planWorkers != PlanWorkers.NONE && workerJar != null && Files.isRegularFile(workerJar)) {
            Path gen = generatedSourceDir != null ? generatedSourceDir : stateDir.resolve("gen");
            Files.createDirectories(gen);
            ForkedJavac.@Nullable Plan plan;
            try {
                ForkedJavac.Request planned = new ForkedJavac.Request(
                                request.compilerHome(),
                                workerJar,
                                request.sources(),
                                request.classpath(),
                                request.processorPath(),
                                Objects.requireNonNull(request.outputDir(), "outputDir"),
                                gen,
                                request.release(),
                                request.extraOptions(),
                                stateDir,
                                request.scalaVersion(),
                                request.compilerClasspath(),
                                request.scalaLibraryJar(),
                                request.scalaCompilerJar(),
                                request.scalaBridgeJar(),
                                env)
                        .withClasspathAnalyses(producerAnalyses(request, stateDir));
                plan = planWorkers == PlanWorkers.WARM ? ForkedJavac.planIfWarm(planned) : ForkedJavac.plan(planned);
            } catch (RuntimeException e) {
                // A plan the worker cannot make (a sibling jar not built yet, say) is a full compile.
                return new Prediction(Outcome.FULL, key, request.sources().size(), "no plan: " + e.getMessage());
            }
            if (plan != null) return fromPlan(plan, key, request);
        }
        Optional<ActionCache.ActionRecord> prior = actionCache.lastFor(taskId, stateDir);
        if (prior.isEmpty()) {
            return new Prediction(Outcome.FULL, key, request.sources().size(), "no prior compile record");
        }
        Map<String, String> in = prior.get().inputs();
        if (!String.valueOf(request.release()).equals(in.getOrDefault("release", null))) {
            return new Prediction(Outcome.FULL, key, request.sources().size(), "release changed");
        }
        if (!ActionKey.optionsToken(request.extraOptions()).equals(in.getOrDefault("options", ""))) {
            return new Prediction(Outcome.FULL, key, request.sources().size(), "javac options changed");
        }
        List<Path> changed = changedSources(request, in);
        if (changed.isEmpty()) {
            // No source moved, yet the key missed: a classpath entry's API or a processor did.
            // Naming it is what keeps why-rebuilt from reading "nothing changed, rebuilt anyway".
            String classpathReason = changedClasspath(request, in);
            if (classpathReason != null) {
                return new Prediction(Outcome.INCREMENTAL, key, 0, classpathReason, List.of());
            }
        }
        int n = changed.size();
        String reason = n == 1 ? "1 source changed" : n + " sources changed";
        return new Prediction(Outcome.INCREMENTAL, key, n, reason, changed);
    }

    private static Prediction fromPlan(ForkedJavac.Plan plan, String key, CompileRequest request) {
        List<Path> files = plan.sources();
        if (plan.full()) {
            return new Prediction(
                    Outcome.FULL, key, files.isEmpty() ? request.sources().size() : files.size(), plan.reason(), files);
        }
        String reason = plan.reason();
        if (reason.isBlank()) {
            int n = files.size();
            reason = n == 1 ? "1 source" : n + " sources";
        }
        return new Prediction(Outcome.INCREMENTAL, key, files.size(), reason, files);
    }

    /**
     * The producer analyses the worker is handed for {@code request}'s classpath. Every Java
     * compile's state dir sits beside this one under the same incremental root, so the root is
     * {@code stateDir}'s parent; a state dir with no parent (a test's bare directory) has no
     * producers to find.
     */
    private static Map<Path, Path> producerAnalyses(CompileRequest request, Path stateDir) {
        Path incrementalRoot = stateDir.toAbsolutePath().normalize().getParent();
        if (incrementalRoot == null) return Map.of();
        return ProducerAnalyses.forClasspath(request.classpath(), incrementalRoot);
    }

    /**
     * Which classpath entries' tokens differ from the prior record's, spelled as the record spells
     * them ({@link ActionKey#snapshotInputs}): {@code cp:} by ABI, {@code pp:} by content. Null when
     * none differ.
     */
    private static @Nullable String changedClasspath(CompileRequest request, Map<String, String> priorInputs)
            throws IOException {
        Map<String, String> now = ActionKey.snapshotInputs(request);
        List<String> api = new ArrayList<>();
        List<String> processors = new ArrayList<>();
        for (Map.Entry<String, String> e : now.entrySet()) {
            String k = e.getKey();
            boolean cp = k.startsWith("cp:");
            if (!cp && !k.startsWith("pp:")) continue;
            if (e.getValue().equals(priorInputs.get(k))) continue;
            Path entry = Path.of(k.substring(3));
            (cp ? api : processors).add(String.valueOf(entry.getFileName()));
        }
        if (api.isEmpty() && processors.isEmpty()) return null;
        if (!api.isEmpty()) return "dependency API changed (" + String.join(", ", api) + ")";
        return "processor changed (" + String.join(", ", processors) + ")";
    }

    private static List<Path> changedSources(CompileRequest request, Map<String, String> priorInputs)
            throws IOException {
        return ActionKey.changedSources(request.sources(), priorInputs);
    }

    /** True when the worker compiled every source of {@code request}: a full compile, not a stale incremental one. */
    private static boolean compiledEverySource(CompileRequest request, List<Path> compiled) {
        Set<Path> done = new HashSet<>();
        for (Path p : compiled) done.add(p.toAbsolutePath().normalize());
        for (Path p : request.sources()) if (!done.contains(p.toAbsolutePath().normalize())) return false;
        return true;
    }

    private static void deleteClasses(Path out) throws IOException {
        if (!Files.isDirectory(out)) return;
        PathUtil.forEachRegularFile(out, (p, attrs) -> {
            if (p.toString().endsWith(".class")) Files.deleteIfExists(p);
        });
    }
}
