// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.GroovycInputs;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.compile.KotlincInputs;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.compile.WorkerCompileDriver;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Action-cache front for the secondary-language compiles (Kotlin, Groovy): a whole-input
 * action-key hit/miss around the worker fork. Both languages share one {@link Result} and one
 * post-fork fold; they differ only in the key, the pre-fork hygiene arm, and the worker dispatch
 * ({@link WorkerCompileDriver} switches on the request type). For Kotlin, the worker owns
 * incremental recompile and the cheap {@code .kstamp} check sits in front of this; the Groovy
 * worker is always a full compile.
 */
public final class LangCompile {

    private LangCompile() {}

    /**
     * Outcome of a {@link #run}. {@code diagnostics} are the worker's, one entry each. {@code
     * movedSources} are the sources whose bytes changed between the key and the worker's return:
     * the compile is not recorded and the next build recompiles them.
     */
    public record Result(
            boolean success,
            String outcome,
            String actionKey,
            List<CompileResult.Diagnostic> diagnostics,
            List<Path> movedSources) {

        public Result {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
            movedSources = movedSources == null ? List.of() : List.copyOf(movedSources);
        }

        /** True when an existing record satisfied the request (no compile ran). */
        public boolean cacheHit() {
            return outcome.startsWith("cache-hit");
        }

        /** Joined diagnostics for logs and exception messages. */
        public String output() {
            return diagnostics.stream().map(CompileResult.Diagnostic::describe).collect(Collectors.joining("\n"));
        }
    }

    /**
     * @param useCache when false ({@code --redo}/{@code --force}), skip restore/skip — still
     * <em>write</em> the action cache after a successful compile so the next explain/build can
     * CACHE_HIT.
     */
    public static Result run(
            String taskId,
            KotlincRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            WorkerEnv env,
            KotlinClasspathAbi.Snapshotter snapshotter)
            throws IOException {
        return run(taskId, request, jkVersion, useCache, true, cas, actionCache, env, snapshotter);
    }

    /**
     * As above with {@code persist}: false for {@code jk verify}'s scratch rebuild, whose
     * scratch-salted keys can never recur — a successful compile must not leave an orphan action
     * record behind.
     */
    public static Result run(
            String taskId,
            KotlincRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            WorkerEnv env,
            KotlinClasspathAbi.Snapshotter snapshotter)
            throws IOException {
        String key = ActionKey.forKotlinc(taskId, request, jkVersion, snapshotter);

        Path workingDir = request.workingDir();
        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            // A failed restore (missing/corrupt blob) falls through to a real compile.
            if (hit.isPresent() && actionCache.restore(hit.get(), request.outputDir())) {
                reconcileState(workingDir, key, hit.get().outputs());
                return cacheHit(key);
            }
        }

        Files.createDirectories(request.outputDir());
        // Incremental state is only valid alongside the outputs it produced: if the output
        // dir is (now) empty of classes while IC state survives (a cleaned target/, a fresh
        // checkout with a warm cache), BTA would compile "only what changed" into the void
        // and report success with a near-empty dir. Start the IC state over instead.
        if (request.incremental()
                && workingDir != null
                && Files.isDirectory(workingDir)
                && (!hasClasses(request.outputDir()) || javaDeclarationsMoved(actionCache, workingDir, request))) {
            PathUtil.deleteRecursively(workingDir);
        }
        return forkAndStore(
                taskId,
                key,
                request.outputDir(),
                request.sources().isEmpty(),
                persist,
                cas,
                actionCache,
                // Recorded for why-rebuilt: the tokens are memoized by the key above, so a lookup each.
                () -> ActionKey.kotlincInputs(request, snapshotter),
                snapshot -> movedKotlinInputs(request, snapshot),
                workingDir,
                () -> WorkerCompileDriver.compile(request, env));
    }

    /**
     * The Kotlin sources whose bytes, and the Java sources whose declarations, differ from the
     * snapshot the key was taken from — what an edit landing while kotlinc ran looks like.
     */
    private static List<Path> movedKotlinInputs(KotlincRequest request, Map<String, String> snapshot)
            throws IOException {
        List<Path> moved = new ArrayList<>(ActionKey.changedSources(request.sources(), snapshot));
        List<Path> javaSources = KotlincInputs.javaSources(request);
        if (javaSources.isEmpty()) return moved;
        Map<Path, String> digests = JavaSourceApi.digests(javaSources);
        for (Path src : javaSources) {
            if (!Objects.equals(digests.get(src), snapshot.get(ActionKey.JAVA_API + PortablePath.key(src)))) {
                moved.add(src);
            }
        }
        return moved;
    }

    /**
     * @param useCache when false ({@code --redo}/{@code --force}), skip restore/skip — still
     * <em>write</em> the action cache after a successful compile so the next explain/build can
     * CACHE_HIT.
     */
    public static Result run(
            String taskId,
            GroovycRequest request,
            String jkVersion,
            boolean useCache,
            Cas cas,
            ActionCache actionCache,
            WorkerEnv env)
            throws IOException {
        return run(taskId, request, jkVersion, useCache, true, cas, actionCache, env);
    }

    /**
     * As above with {@code persist}: false for {@code jk verify}'s scratch rebuild, whose
     * scratch-salted keys can never recur — a successful compile must not leave an orphan action
     * record behind.
     */
    public static Result run(
            String taskId,
            GroovycRequest request,
            String jkVersion,
            boolean useCache,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            WorkerEnv env)
            throws IOException {
        String key = ActionKey.forGroovyc(taskId, request, jkVersion);

        if (useCache) {
            Optional<ActionCache.ActionRecord> hit = actionCache.lookup(key);
            if (hit.isPresent()) {
                // Restore into a CLEAN dir: the worker is a full compile, so anything already
                // here is a previous source set — a deleted.groovy's class would resurrect
                // through the assemble merge and poison later records. A failed restore
                // (missing/corrupt blob) falls through to the real compile below.
                wipe(request);
                if (actionCache.restore(hit.get(), request.outputDir())) {
                    return cacheHit(key);
                }
            }
        }
        wipe(request);
        return forkAndStore(
                taskId,
                key,
                request.outputDir(),
                request.sources().isEmpty(),
                persist,
                cas,
                actionCache,
                () -> ActionKey.snapshotInputs(request),
                snapshot -> ActionKey.changedSources(GroovycInputs.compileSet(request), snapshot),
                null,
                () -> WorkerCompileDriver.compile(request, env));
    }

    /**
     * The shared post-hygiene fold: snapshot the inputs the key hashed, prewrite the CAS while
     * the worker runs, then judge and store. {@code inputs} is what the record remembers of the
     * request, for {@code jk why-rebuilt}; {@code moved} names the sources whose bytes differ from
     * that snapshot once the worker has returned. A compile with a moved source is not recorded —
     * the key names bytes the worker may not have read — and {@code stateDir}, the incremental
     * state that would pair the earlier hash with the later classes, is dropped.
     */
    private static Result forkAndStore(
            String taskId,
            String key,
            Path outputDir,
            boolean noSources,
            boolean persist,
            Cas cas,
            ActionCache actionCache,
            Inputs inputs,
            Moved moved,
            @Nullable Path stateDir,
            Supplier<CompileResult> fork)
            throws IOException {
        Map<String, String> snapshot = inputs.snapshot();
        // Stream the worker's output dir into the CAS as it's produced, then
        // snapshot the whole dir for the record.
        Files.createDirectories(outputDir);
        CasPrewriter prewriter = CasPrewriter.watching(cas, outputDir);
        CompileResult cr;
        Map<String, String> outputs;
        try {
            cr = fork.get();
        } finally {
            outputs = prewriter.finish();
        }
        if (!cr.success()) {
            return new Result(false, "errors", key, cr.diagnostics(), List.of());
        }
        // Never cache a zero-output "success" for a non-empty source set: a compiler convinced
        // nothing changed (stale incremental state) can report success over an empty output
        // dir, and caching that poisons every later run under the same key.
        if (outputs.isEmpty() && !noSources) {
            return new Result(true, "compiled-no-outputs", key, cr.diagnostics(), List.of());
        }
        List<Path> movedSources = moved.since(snapshot);
        if (!movedSources.isEmpty()) {
            if (stateDir != null && Files.isDirectory(stateDir)) PathUtil.deleteRecursively(stateDir);
            return new Result(true, "compiled", key, cr.diagnostics(), movedSources);
        }
        if (stateDir != null && Files.isDirectory(stateDir)) recordTree(stateDir, key, outputs);
        // Store on rebuild/force too: the work re-ran and must refresh the action pointer so
        // the next non-rebuild explain sees CACHE_HIT (same as JavaCompile). Only
        // ephemeral (verify-scratch) runs skip the write — their keys never recur.
        if (persist) actionCache.storeWithOutputs(taskId, key, snapshot, outputs);
        return new Result(true, "compiled", key, cr.diagnostics(), List.of());
    }

    /** The inputs a stored record carries for {@code jk why-rebuilt}, taken before the worker reads a source. */
    @FunctionalInterface
    private interface Inputs {
        Map<String, String> snapshot() throws IOException;
    }

    /** The sources whose bytes differ from a snapshot, asked once the worker has returned. */
    @FunctionalInterface
    private interface Moved {
        List<Path> since(Map<String, String> snapshot) throws IOException;
    }

    private static Result cacheHit(String key) {
        return new Result(true, "cache-hit:" + key.substring(0, 8), key, List.of(), List.of());
    }

    /**
     * The file in an incremental state dir naming the compile that owns the state and the tree it
     * wrote: the action key on the first line, then one {@code <hash> <relative path>} line per
     * output, in path order; see {@link #recordTree}.
     */
    static final String TREE_LEDGER = "tree";

    /**
     * Note in {@code stateDir} which compile the state belongs to — its action {@code key} — and
     * the tree it wrote — {@code outputs}, relative path to content hash, as the action record
     * stores it — so a later restore can tell what the state describes of what is on disk.
     */
    static void recordTree(Path stateDir, String key, Map<String, String> outputs) throws IOException {
        StringBuilder sb = new StringBuilder(key).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(outputs).entrySet()) {
            sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        }
        Files.writeString(stateDir.resolve(TREE_LEDGER), sb.toString());
    }

    /**
     * Reconcile the incremental state with the tree a restore has laid down: the record's outputs
     * and nothing else, a restore having pruned every file it does not own. A restore tells the
     * state nothing, so when the trees differ the state is read against the record's key. A record
     * under another key names other inputs than the state's compile did, and the state's own
     * change tracking — sources against its snapshot, classpath entries against their ABI
     * snapshots, the compiler's arguments — recompiles what moved between the two compiles on the
     * next edit and deletes what a since-removed source wrote, so the state stays and the next edit
     * is incremental. A record under the very same key that wrote another tree is a compile the
     * state cannot explain, and a state with no ledger vouches for nothing: both start over, and a
     * rebuild from no state clears the output dir before it writes.
     */
    private static void reconcileState(@Nullable Path stateDir, String key, Map<String, String> restored)
            throws IOException {
        if (stateDir == null || !Files.isDirectory(stateDir)) return;
        Ledger produced = readLedger(stateDir.resolve(TREE_LEDGER));
        if (produced == null
                || produced.key().equals(key) && !produced.outputs().equals(restored)) {
            PathUtil.deleteRecursively(stateDir);
        }
    }

    /** What a state's ledger recorded: the owning compile's key and the tree it wrote. */
    record Ledger(String key, Map<String, String> outputs) {}

    /** The ledger at {@code file}, or {@code null} when there is none or it does not read as one. */
    static @Nullable Ledger readLedger(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty() || lines.get(0).isBlank()) return null;
        Map<String, String> outputs = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            int space = line.indexOf(' ');
            if (space <= 0 || space == line.length() - 1) return null;
            outputs.put(line.substring(space + 1), line.substring(0, space));
        }
        return new Ledger(lines.get(0).strip(), outputs);
    }

    /**
     * True when the Java declarations kotlinc reads through {@code -Xjava-source-roots} differ from
     * the ones the compile that produced {@code stateDir} read. The incremental state tracks Kotlin
     * sources and classpath snapshots; a Java signature it read from source is invisible to it, so
     * an incremental compile after such an edit finds nothing to do and leaves Kotlin classes linked
     * against a declaration that no longer exists. The state is started over instead, and the full
     * compile reads the new declarations.
     *
     * <p>The compile asked is the one the state's own {@link #TREE_LEDGER} names, never the task's
     * shared pointer: that pointer is whichever checkout of the project stored last, and its record
     * can agree with this checkout's Java sources while the state here was linked against older
     * ones. A state with no ledger, or whose record is gone, is treated as moved: nothing vouches
     * for it.
     */
    static boolean javaDeclarationsMoved(ActionCache actionCache, Path stateDir, KotlincRequest request)
            throws IOException {
        if (request.javaSourceRoots().isEmpty()) return false;
        Ledger ledger = readLedger(stateDir.resolve(TREE_LEDGER));
        if (ledger == null) return true;
        Optional<ActionCache.ActionRecord> own = actionCache.lookup(ledger.key());
        if (own.isEmpty()) return true;
        return javaDeclarationsMoved(own.get().inputs(), request);
    }

    /** {@link #javaDeclarationsMoved(ActionCache, Path, KotlincRequest)} against a record's inputs. */
    static boolean javaDeclarationsMoved(Map<String, String> priorInputs, KotlincRequest request) throws IOException {
        List<Path> javaSources = KotlincInputs.javaSources(request);
        Map<Path, String> digests = JavaSourceApi.digests(javaSources);
        Map<String, String> now = new HashMap<>();
        for (Path src : javaSources) {
            now.put(ActionKey.JAVA_API + PortablePath.key(src), Objects.requireNonNull(digests.get(src), "digest"));
        }
        Map<String, String> recorded = new HashMap<>();
        for (Map.Entry<String, String> e : priorInputs.entrySet()) {
            if (e.getKey().startsWith(ActionKey.JAVA_API)) recorded.put(e.getKey(), e.getValue());
        }
        return !now.equals(recorded);
    }

    /** Any {@code .class} anywhere under {@code dir}? */
    private static boolean hasClasses(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(f -> f.toString().endsWith(".class"));
        }
    }

    /**
     * Full-recompile lane: the output and stub dirs hold exactly one compile's results. Stale
     * stubs are the worse half — javac resolves deleted Groovy types from {@code --source-path}
     * stubs and ships stub-bodied phantom classes instead of erroring.
     */
    private static void wipe(GroovycRequest request) throws IOException {
        PathUtil.deleteRecursively(request.outputDir());
        Files.createDirectories(request.outputDir());
        if (request.stubsOut() != null) {
            PathUtil.deleteRecursively(request.stubsOut());
            Files.createDirectories(request.stubsOut());
        }
    }
}
