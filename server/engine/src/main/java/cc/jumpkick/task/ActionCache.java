// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.StepKinds;
import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.IoLedger;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.TreeContent;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.StepCache;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ToolchainPolicy.Expectation;
import cc.jumpkick.util.FileLocks;
import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Persistent action cache: {@code action_key → CAS outputs} plus a project-qualified {@code task
 * → action_key} pointer. Layout: {@code keys/<actionKey>}, {@code tasks/<taskId>}. Store/restore
 * always copy (never hard-link) so compile trees cannot poison CAS blobs.
 *
 * <p>Optional {@code storeCas}: Class-C blobs promoted by {@link cc.jumpkick.cache.ActionPromote} /
 * Staged natives/jars may live only in the artifact store — restore falls back there on cache miss.
 * Class-C tasks also keep a short generation list per checkout ({@code tasks/<taskId>.gens}, see
 * {@link HeavyActionPolicy}).
 *
 * <p>Records and blobs are read and written through {@link LocalCacheBackend}; when the build has
 * bound a {@link RemoteTier} ({@link RemoteCaches}), a key the local disk cannot restore is asked
 * of it next.
 */
public final class ActionCache {

    /**
     * Ceiling on concurrent output deposits. The work is filesystem latency, not CPU, so the useful
     * width is set by what the device will absorb rather than by cores; past this the copies queue
     * on the device and the extra threads only add contention.
     */
    private static final int DEPOSIT_LANES = 16;

    /** Files a lane must be worth before one is opened; below this the deposit runs on the caller. */
    private static final int MIN_FILES_PER_DEPOSIT_LANE = 32;

    private final Cas cas;
    private final @Nullable Cas storeCas; // dual-CAS lookup for promoted Class-C blobs
    private final Path root;

    /** Records and blobs on this machine's disk: every read asks here first, every store lands here. */
    private final LocalCacheBackend local;

    /** The build's remote tier, asked after {@link #local} misses; null when the build has none. */
    private final @Nullable RemoteTier remote;

    public ActionCache(Cas cas, Path root) {
        this(cas, root, null);
    }

    /**
     * @param storeCas optional long-lived store CAS for Class-C blob fallback (may be null)
     */
    public ActionCache(Cas cas, Path root, @Nullable Cas storeCas) {
        this.cas = Objects.requireNonNull(cas, "cas");
        this.root = Objects.requireNonNull(root, "root");
        this.storeCas = storeCas;
        this.local = new LocalCacheBackend(cas, root, storeCas);
        this.remote = RemoteCaches.current();
        if (remote != null) remote.bindLocal(local);
    }

    /**
     * CAS pool this action cache stores/restores payloads in — must be the <em>cache</em> CAS
     * ({@link cc.jumpkick.cache.JkStores#cacheCas}), never the artifact store.
     */
    public Cas cas() {
        return cas;
    }

    /** The directory this action cache keeps its records under, on the volume its blobs share. */
    public Path root() {
        return root;
    }

    /**
     * Resolve every blob in {@code outputs} once, with its size.
     *
     * <p>Presence, {@link #meter}, and the copy share this map so each blob is {@code readAttributes}
     * once. Empty when any blob is missing: a restore cannot proceed without all of them.
     */
    private Optional<Map<String, CachedBlob>> resolveAll(Map<String, String> outputs) {
        Map<String, CachedBlob> blobs = new HashMap<>();
        for (String sha : outputs.values()) {
            if (blobs.containsKey(sha)) continue;
            if (!isSha256Hex(sha)) return Optional.empty(); // a marker record restores nothing
            CachedBlob b = blob(sha);
            if (b == null) return Optional.empty();
            blobs.put(sha, b);
        }
        return Optional.of(blobs);
    }

    /**
     * {@code sha}'s blob in the cache CAS, else the store CAS (promoted Class-C), else {@code null}.
     * One {@code readAttributes} per candidate answers existence, regular-file-ness, and size.
     */
    private @Nullable CachedBlob blob(String sha) {
        CachedBlob hit = CachedBlob.at(cas.pathFor(sha));
        if (hit != null) return hit;
        return storeCas == null ? null : CachedBlob.at(storeCas.pathFor(sha));
    }

    /**
     * The record for {@code actionKey} when a restore of it would succeed: the record exists and
     * every output blob it names is present (cache CAS or store CAS, as {@link #resolveBlob}
     * finds it). A record whose payload was pruned is a miss: the build would compile, not
     * restore. The forecast and every step's cache check ask this one question, so a prediction
     * can never call a hit that the restore then refuses.
     *
     * <p>When this disk cannot restore it, the build's remote tier is asked: a record it holds
     * whole is restorable, and the restore fetches the blobs this disk lacks.
     */
    public Optional<ActionRecord> restorable(@Nullable String actionKey) {
        return actionKey == null ? Optional.empty() : restorable(actionKey, ToolchainPolicy.of(actionKey));
    }

    /** As {@link #restorable(String)} under {@code expected}: for a caller off a job's request (the forecast). */
    public Optional<ActionRecord> restorable(String actionKey, @Nullable Expectation expected) {
        try {
            Optional<ActionRecord> rec = lookupLocal(actionKey, expected);
            if (rec.isPresent() && local.missing(rec.get().outputs().values()).isEmpty()) return rec;
            return lookupRemote(actionKey, expected);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Why no record of {@code actionKey} kept here serves {@code expected}'s reader; null when one does. */
    public @Nullable String declined(String actionKey, Expectation expected) {
        return ToolchainChoice.declined(local, actionKey, expected);
    }

    /** Resolve a blob path: cache CAS first, then store CAS (promoted Class-C). */
    Path resolveBlob(String sha) {
        return local.blobPath(sha);
    }

    /**
     * The record for {@code actionKey}, or empty when there is none: this disk's, else the build's
     * remote tier's. Reads without a prior existence check: another engine's {@link
     * ActionCachePrune} can unlink the key between the two, and a vanished entry is a miss, not a
     * failure. A hit {@linkplain ActionRecordText#markRead marks the record read}, so the prune
     * ranks it by last use.
     */
    public Optional<ActionRecord> lookup(String actionKey) throws IOException {
        Expectation expected = ToolchainPolicy.of(actionKey);
        Optional<ActionRecord> here = lookupLocal(actionKey, expected);
        Optional<ActionRecord> found = here.isEmpty() ? lookupRemote(actionKey, expected) : here;
        boolean served = here.isEmpty() || RemoteTier.adoptedThisBuild(remote, actionKey);
        if (found.isPresent() && StepCacheNotes.inStep()) {
            ActionRecordText.Writes kind = found.get().meta().writes();
            // A marker or verdict is the result itself: reading it is the step's hit, unless the
            // step goes on to run and store.
            if (kind == ActionRecordText.Writes.TEST_MARKER || kind == ActionRecordText.Writes.VERDICT) {
                StepCacheNotes.replayed(actionKey, served, found.get().meta().wallMillis());
            }
        }
        return found;
    }

    private Optional<ActionRecord> lookupLocal(String actionKey, @Nullable Expectation expected) throws IOException {
        Optional<String> content = local.record(actionKey);
        if (content.isEmpty()) return Optional.empty();
        content = ToolchainChoice.choose(local, actionKey, content.get(), expected);
        if (content.isEmpty()) return Optional.empty();
        ActionRecord record;
        try {
            record = parse(content.get(), actionKey);
        } catch (ActionRecordText.Refused refused) {
            // Whole but not this engine's to trust (a restored or imported cache): a miss, said
            // once, since the record goes with it.
            Log.warn("jk: action record refused; rebuilding", "key", actionKey, "cause", refused.getMessage());
            StepCacheNotes.refused();
            local.discard(actionKey);
            return Optional.empty();
        } catch (RuntimeException torn) {
            // A record cut short — an engine killed mid-write, a full disk — is a miss, not a
            // failure of every step that consults this key on every build until --rebuild. The
            // file goes so the next real run's store is what the key names.
            local.discard(actionKey);
            return Optional.empty();
        }
        ActionRecordText.markRead(local.recordPath(actionKey));
        if (expected != null) ToolchainChoice.madeOn(actionKey, record.meta(), expected);
        return Optional.of(record);
    }

    /**
     * The remote tier's record for {@code actionKey}, read as untrusted as a local one is. A record
     * refused here is a miss for this build and stays where it is: no reader deletes from a store
     * others share. One that names no blob this disk lacks (a verdict, a test marker) is kept here
     * at once, so the next build finds it locally.
     */
    private Optional<ActionRecord> lookupRemote(String actionKey, @Nullable Expectation expected) throws IOException {
        Held held = heldRemotely(actionKey, expected);
        if (held == null) return Optional.empty();
        if (local.missing(held.record().outputs().values()).isEmpty()) adopt(held.record(), held.text());
        if (expected != null) ToolchainChoice.madeOn(actionKey, held.record().meta(), expected);
        return Optional.of(held.record());
    }

    /** A remote record this machine takes, and the text it was read from. */
    private record Held(ActionRecord record, String text) {}

    /** The remote record of {@code actionKey} when it parses and suits {@code expected} and the platform; else null. */
    private @Nullable Held heldRemotely(String actionKey, @Nullable Expectation expected) {
        if (remote == null || actionKey.isBlank()) return null;
        String text = remote.recordFor(actionKey, expected);
        if (text == null) return null;
        ActionRecord record;
        try {
            record = parse(text, actionKey);
        } catch (RuntimeException refused) {
            Log.warn("jk: remote action record refused", "key", actionKey, "cause", refused.getMessage());
            remote.forget(actionKey);
            return null;
        }
        if (ToolchainChoice.refuses(actionKey, record.meta().toolchain(), expected)) {
            remote.declined(actionKey, record.meta().toolchain()); // a miss here, kept for the readers it suits
            return null;
        }
        if (!forThisPlatform(record.meta().target(), remote.platform())) {
            // Outputs made for another platform: a miss here, and the store keeps them for readers
            // they are for. A record naming no target runs anywhere.
            remote.forget(actionKey);
            return null;
        }
        return new Held(record, text);
    }

    /**
     * Whether outputs for {@code target} may be used on {@code platform}: always when the record
     * names no target, else only when it names this platform exactly. A target this jk cannot
     * compare (one naming a libc) is never taken, so the rule errs towards a rebuild.
     */
    static boolean forThisPlatform(@Nullable String target, String platform) {
        return target == null || target.equals(platform);
    }

    /**
     * Keep the remote tier's {@code record} (its text {@code text}) on this disk, its blobs already
     * here, and point its task at it when no record made here does yet, so the step's next miss
     * names the inputs that moved since rather than {@code no-record}.
     */
    private void adopt(ActionRecord record, String text) throws IOException {
        String key = Objects.requireNonNull(record.actionKey(), "actionKey");
        local.putRecord(key, text);
        if (ToolchainPolicy.of(key) != null) local.putVariant(key, record.meta().toolchain(), text);
        if (record.taskId() != null && !Files.exists(tasksDir().resolve(record.taskId()))) pointAt(record);
        if (remote != null) remote.adopted(key);
    }

    /**
     * When this disk lacks a blob of {@code record}, fetch the blobs of the record the remote tier
     * holds for its key, verified, and keep that record here: read as {@link #lookupRemote} reads
     * one, so a record another JDK or platform made never replaces this disk's. False when there is
     * no tier, it holds no record this machine takes, or a blob did not arrive: the restore is then
     * the miss it would have been.
     */
    private boolean fetchFromRemote(ActionRecord record) throws IOException {
        String key = record.actionKey();
        if (remote == null
                || key == null
                || local.missing(record.outputs().values()).isEmpty()) return false;
        Held held = heldRemotely(key, ToolchainPolicy.of(key));
        if (held == null) return false;
        Set<String> missing = local.missing(held.record().outputs().values());
        if (!missing.isEmpty() && !remote.fetch(held.record(), missing, cas)) return false;
        adopt(held.record(), held.text());
        return true;
    }

    /** The record the {@code tasks/} pointer for {@code taskId} names, or empty. Same race, same answer. */
    public Optional<ActionRecord> lastFor(String taskId) throws IOException {
        String actionKey;
        try {
            actionKey = AtomicWrites.readString(tasksDir().resolve(taskId)).trim();
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException denied) {
            if (!LocalCacheBackend.deniedByAConcurrentReplace(denied)) throw denied;
            return Optional.empty();
        }
        return lookup(actionKey);
    }

    /**
     * The record of the compile whose incremental state {@code stateDir} holds — the one its tree
     * ledger names — falling back to the task pointer's when the state has no ledger or the record
     * is gone. The pointer is shared by every checkout of the project; the ledger is this
     * checkout's, so a forecast explains a rebuild against this checkout's own last compile.
     */
    public Optional<ActionRecord> lastFor(String taskId, @Nullable Path stateDir) throws IOException {
        if (stateDir != null) {
            LangCompile.Ledger ledger = LangCompile.readLedger(stateDir.resolve(LangCompile.TREE_LEDGER));
            if (ledger != null) {
                Optional<ActionRecord> own = lookup(ledger.key());
                if (own.isPresent()) return own;
            }
        }
        return lastFor(taskId);
    }

    /**
     * Compute output hashes from {@code outputDir}, deposit each file in the CAS, and write the
     * {@link ActionRecord}. After this, callers can later restore the same outputs via {@link
     * #restore}.
     */
    public ActionRecord store(String taskId, String actionKey, Map<String, String> inputs, Path outputDir)
            throws IOException {
        return store(taskId, actionKey, inputs, outputDir, List.of());
    }

    /**
     * As {@link #store(String, String, Map, Path)}, keeping {@code said} — what the action told the
     * user as it ran — in the record, so a {@linkplain ActionRecord#said() hit} can say it again.
     */
    public ActionRecord store(
            String taskId, String actionKey, Map<String, String> inputs, Path outputDir, List<String> said)
            throws IOException {
        Map<String, String> outputs = new TreeMap<>();
        Set<String> executables = new TreeSet<>();
        if (Files.exists(outputDir)) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> stream = Files.find(outputDir, Integer.MAX_VALUE, (p, attrs) -> attrs.isRegularFile())) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    // Freshness stamps and `.jk-*` scratch directories (a plugin's private
                    // bootstrap repo/staging) are never action outputs; every other file is,
                    // dot-files included — the same set a key fingerprints and a packager ships.
                    if (!TreeContent.isContent(outputDir.relativize(file))) continue;
                    files.add(file);
                }
            }
            if (!DiskGuard.admits(root, files)) return new ActionRecord(taskId, actionKey, inputs, Map.of());
            deposit(files, outputDir, outputs, executables);
        }
        // Refuse empty success records, whatever the inputs look like: an action that produced
        // zero files must not become a hit that restores an empty tree on the next build — for
        // a plugin step whose scratch dir replaces the classes dir, that is a jar with nothing
        // in it. A check whose whole result is "nothing to say" records that through
        // storeVerdict, where the caller asserts there is nothing to restore.
        if (outputs.isEmpty()) {
            return new ActionRecord(taskId, actionKey, inputs, Map.of());
        }
        return storeWithOutputs(
                ActionRecordText.Writes.OUTPUTS, taskId, actionKey, inputs, outputs, executables, outputDir, said);
    }

    /**
     * Hash and deposit every output, then fill {@code outputs} / {@code executables}.
     *
     * <p><strong>Concurrent, because the deposit is the slow part and nothing in it is shared.</strong>
     * Each blob is an independent copy into its own content-addressed name, idempotent and guarded by
     * its own {@code exists()}; a cold store of this tree's ~7,500 outputs is seconds of pure
     * filesystem latency on a host where a create costs 12x what it does on Linux. Lanes are sized off
     * the file count so a small module never pays for threads it cannot use, and every deposit is
     * joined before the caller writes the record — a record naming a blob that is not on disk is not
     * recoverable by re-running.
     *
     * <p>{@link JkThreads#platform()} rather than {@code cpu()}: this waits on the filesystem, and the
     * CPU pool is what the compile lanes want; not {@code io()}, because hashing a file is a native
     * read the virtual-thread scheduler does not compensate for.
     */
    private void deposit(List<Path> files, Path outputDir, Map<String, String> outputs, Set<String> executables)
            throws IOException {
        int lanes = Math.clamp(files.size() / MIN_FILES_PER_DEPOSIT_LANE, 1, DEPOSIT_LANES);
        Map<String, String> hashes = new ConcurrentHashMap<>();
        Set<String> executableRel = ConcurrentHashMap.newKeySet();
        if (lanes == 1) {
            for (Path file : files) depositOne(file, outputDir, hashes, executableRel);
        } else {
            List<Future<?>> pending = new ArrayList<>(lanes);
            int chunk = (files.size() + lanes - 1) / lanes;
            for (int lane = 0; lane < lanes; lane++) {
                List<Path> slice =
                        files.subList(Math.min(lane * chunk, files.size()), Math.min((lane + 1) * chunk, files.size()));
                pending.add(JkThreads.platform().submit(() -> {
                    for (Path file : slice) depositOne(file, outputDir, hashes, executableRel);
                    return null;
                }));
            }
            join(pending);
        }
        outputs.putAll(hashes);
        executables.addAll(executableRel);
    }

    /** Hash one output, put it in the CAS, and record its relative path. */
    private void depositOne(Path file, Path outputDir, Map<String, String> hashes, Set<String> executableRel)
            throws IOException {
        // Through the memo: a warm rebuild answers from the map without reading the output, and
        // putFile skips the copy for a blob already present.
        String hex = FileHashMemo.contentHash(file);
        local.putBlob(hex, file);
        // Seed the memo, which refuses a file written inside its settle window on its own.
        FileHashMemo.rememberContent(file, hex);
        String relPath = outputDir.relativize(file).toString().replace(File.separatorChar, '/');
        hashes.put(relPath, hex);
        if (executableBit(file)) executableRel.add(relPath);
    }

    /**
     * Wait for every deposit lane, surfacing the first failure as the {@code IOException} the caller
     * expects. A lane that failed must not leave the others running into a record write.
     */
    private static void join(List<Future<?>> pending) throws IOException {
        IOException failure = null;
        for (Future<?> f : pending) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted depositing outputs", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (failure == null) {
                    failure = cause instanceof IOException io ? io : new IOException(cause);
                }
            }
        }
        if (failure != null) throw failure;
    }

    /**
     * Whether {@code file} carries an executable bit worth recording. Windows has none that survives
     * a restore — the restore calls {@link java.io.File#setExecutable}, which does nothing there —
     * and asking costs a security-descriptor read plus an access check per output file.
     */
    private static boolean executableBit(Path file) {
        // Deliberately Files.isExecutable and not PathUtil.isRunnable: the question here is
        // "is there a bit worth recording for restore", not "can this host run it". isRunnable answers
        // true for a .exe on Windows, and recording that would promise a bit the restore cannot set —
        // File.setExecutable does nothing there. The !isWindows() guard already skips the 64x call on
        // the platform where it costs, which is the whole reason the ban has an exception here.
        return !Os.isWindows() && Files.isExecutable(file);
    }

    /**
     * Record that an action <strong>succeeded and produced nothing</strong> — a verdict, not an
     * artifact.
     *
     * <p>{@link #store} refuses exactly this shape, and rightly: a compile with sources that
     * emitted zero classes must never become a hit that restores an empty tree. But a check is not
     * a compile. It scans, it throws or it does not, and "these inputs are clean" is the whole
     * result — the only thing worth remembering about it. Without a door for that, every such
     * action re-runs on every build forever, and the caller's only alternative is to fabricate an
     * output nobody reads.
     *
     * <p>Separate method rather than a flag on {@code store}, so the decision is visible at the
     * call site: a caller reaching for this is asserting there is nothing to restore, which the
     * output-dir walk cannot tell it.
     */
    public ActionRecord storeVerdict(String taskId, String actionKey, Map<String, String> inputs) throws IOException {
        return storeVerdict(taskId, actionKey, inputs, List.of());
    }

    /** As {@link #storeVerdict(String, String, Map)}, keeping what the action {@linkplain ActionRecord#said() said}. */
    public ActionRecord storeVerdict(String taskId, String actionKey, Map<String, String> inputs, List<String> said)
            throws IOException {
        return storeWithOutputs(
                ActionRecordText.Writes.VERDICT, taskId, actionKey, inputs, Map.of(), Set.of(), null, said);
    }

    /**
     * Write an action record using a pre-computed {@code outputs} map — used by callers that already
     * CAS'd the files via {@link CasPrewriter} (or anything else that hashed + copied while the
     * action was still running). Skips the output-dir walk; just writes the manifest and pointer.
     * These callers store no Class-C task, so no generation list is kept for them.
     */
    public ActionRecord storeWithOutputs(
            String taskId, String actionKey, Map<String, String> inputs, Map<String, String> outputs)
            throws IOException {
        return storeWithOutputs(taskId, actionKey, inputs, outputs, List.of());
    }

    /** As {@link #storeWithOutputs(String, String, Map, Map)}, keeping what the action {@linkplain ActionRecord#said() said}. */
    public ActionRecord storeWithOutputs(
            String taskId, String actionKey, Map<String, String> inputs, Map<String, String> outputs, List<String> said)
            throws IOException {
        long bytes = new HashSet<>(outputs.values())
                .stream().mapToLong(this::blobSize).sum();
        if (!DiskGuard.admits(root, bytes)) return new ActionRecord(taskId, actionKey, inputs, Map.of());
        return storeWithOutputs(kindOf(outputs), taskId, actionKey, inputs, outputs, Set.of(), null, said);
    }

    /**
     * Record a suite's outcome: a {@link ActionRecordText.Writes#TEST_MARKER} of counts, or a {@link
     * ActionRecordText.Writes#CLASS_LEDGER} of per-class outcomes (none when cleared). {@code markers}
     * are scalars, never blobs, and the kind is what keeps a red one on this machine.
     */
    public ActionRecord storeMarkers(
            ActionRecordText.Writes kind,
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> markers,
            List<String> said)
            throws IOException {
        if (kind != ActionRecordText.Writes.TEST_MARKER && kind != ActionRecordText.Writes.CLASS_LEDGER) {
            throw new IllegalArgumentException("not a marker kind: " + kind);
        }
        Map<String, String> named = inputs.isEmpty() ? StepCacheNotes.derivedFrom(actionKey) : inputs;
        return storeWithOutputs(kind, taskId, actionKey, named, markers, Set.of(), null, said);
    }

    /**
     * The named tokens the running step derived {@code key} from ({@link KeyAudit#tapped}), for a
     * marker stored under it without inputs of its own: a suite's stamp is a hash of many lines, and
     * its record names them so a miss can say which moved. Environment values are withheld, as in
     * the shared form; outside a step nothing is kept.
     */
    public static void derivedFrom(String key, Map<String, String> tokens) {
        Map<String, String> named = new TreeMap<>();
        tokens.forEach((name, value) ->
                named.put(ActionRecordText.withoutEnvValues(name), ActionRecordText.withoutEnvValues(value)));
        StepCacheNotes.derived(key, named);
    }

    /** A verdict when {@code outputs} is empty, outputs when every value is a blob, else of no kind. */
    private static ActionRecordText.@Nullable Writes kindOf(Map<String, String> outputs) {
        if (outputs.isEmpty()) return ActionRecordText.Writes.VERDICT;
        for (String value : outputs.values()) {
            if (!isSha256Hex(value)) return null;
        }
        return ActionRecordText.Writes.OUTPUTS;
    }

    /**
     * Point {@code record}'s task at it: a restore laid its outputs down, so {@link #lastFor} names
     * what is on disk rather than the last record a run stored, which may be another key's with
     * other files (a Next.js build names its chunks by content), and a check that every file of the
     * last record is there would schedule the module on every build.
     */
    public void pointAt(ActionRecord record) throws IOException {
        String task = Objects.requireNonNull(record.taskId(), "taskId");
        String key = Objects.requireNonNull(record.actionKey(), "actionKey");
        if (HeavyActionPolicy.generations(task) != Integer.MAX_VALUE) return;
        Files.createDirectories(tasksDir());
        AtomicWrites.replace(tasksDir().resolve(task), key);
    }

    /**
     * Store the record held under {@code fromKey} again under {@code actionKey}, with {@code inputs}:
     * the same outputs, executables and words, for a step whose inputs the next build reads without
     * files the build itself wrote. Nothing is stored when {@code fromKey} holds no record (a store
     * the disk guard refused), so an alias never names outputs the cache does not have.
     */
    public void storeAlias(String fromKey, String actionKey, Map<String, String> inputs) throws IOException {
        Optional<ActionRecord> from = lookup(fromKey);
        if (from.isEmpty()) return;
        ActionRecord r = from.get();
        storeWithOutputs(
                ActionRecordText.Writes.ALIAS,
                r.taskId(),
                actionKey,
                inputs,
                r.outputs(),
                r.executables(),
                null,
                r.said());
    }

    /**
     * The store every path funnels through: the record, then the task pointer, then the Class-C
     * generation trim. {@code executables} are the outputs a restore gives the bit back to; {@code
     * outputRoot} is the tree the outputs were written under, which names the checkout a Class-C
     * generation belongs to; {@code said} is what the action told the user as it ran.
     */
    private ActionRecord storeWithOutputs(
            ActionRecordText.@Nullable Writes kind,
            @Nullable String taskId,
            @Nullable String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Set<String> executables,
            @Nullable Path outputRoot,
            List<String> said)
            throws IOException {
        Files.createDirectories(tasksDir());
        // Every store path funnels here: one stat per output answers the record's sizes, the run's
        // cache-in bytes and the tier's classification.
        Map<String, Long> sizes = outputSizes(outputs);
        long storedBytes = 0;
        for (long size : sizes.values()) storedBytes += size;
        SessionContext.current().io().localUp(storedBytes);
        Map<String, Long> bySha = new HashMap<>();
        for (var e : sizes.entrySet()) bySha.put(outputs.get(e.getKey()), e.getValue());
        String task = Objects.requireNonNull(taskId, "taskId");
        CacheStats.store(CacheStats.classify(task, outputs, sha -> bySha.getOrDefault(sha, 0L)), storedBytes);
        String key = Objects.requireNonNull(actionKey, "actionKey");
        if (VerifyCache.on()) VerifyCache.check(this, task, key, outputs);
        // A class ledger rides beside its suite's marker, stored first: the marker says why the suite ran.
        if (StepCacheNotes.inStep() && kind != ActionRecordText.Writes.CLASS_LEDGER)
            noteStored(task, key, inputs, storedBytes);
        ActionRecordText.Meta meta = ActionRecordText.Meta.now(
                StepKinds.of(task), kind, producer(), CachePlatform.current(), workedMillis(), sizes);
        meta = RecordToolchain.withPlatform(meta, key, inputs);
        // Only a record that may leave the machine pays for its tokens: here the blobs are on disk.
        if (remote != null && remote.offers(key))
            meta = meta.withDerived(DerivedTokens.of(meta.kind(), outputs, cas, local::blobPath));
        ActionRecord record = new ActionRecord(task, key, inputs, outputs, executables, said, meta);
        // Atomic temp+move: concurrent store/lookup under cacheGate read mode must never see a
        // truncated keys/ or tasks/ file. Order preserved: key before task pointer.
        try {
            String text = render(record);
            local.putRecord(key, text);
            if (ToolchainPolicy.of(key) != null) local.putVariant(key, meta.toolchain(), text);
            Path pointer = tasksDir().resolve(task);
            if (HeavyActionPolicy.generations(task) == Integer.MAX_VALUE || outputRoot == null) {
                AtomicWrites.replace(pointer, key); // not Class-C: no generation list to agree with
            } else {
                trimGenerations(task, key, outputRoot, () -> AtomicWrites.replace(pointer, key));
            }
        } catch (AccessDeniedException denied) {
            // POSIX EACCES is a permissions problem and stays loud: waiting cannot clear it and
            // hiding it would leave a cache that silently never stores. Only Windows denies a
            // replace for a reason that passes.
            if (!Os.isWindows()) throw denied;
            // A reader held one of these names for longer than AtomicWrites was willing to retry.
            // Publishing the record is what makes the next build skip this action, so failing to
            // publish costs that build a rebuild — and throwing here would cost THIS build a step
            // that has already succeeded and whose outputs are already in the CAS. The record is
            // returned either way; it describes what was produced, not what is on disk. Either
            // half landing on its own is a miss, never a wrong hit: a key with no pointer is found
            // by key, and a pointer naming a key that is not there reads as empty in lookup.
            // Warn rather than debug: with the retry budget AtomicWrites now uses, a measured
            // eight writers against two looping readers lost none, so reaching here at all is
            // worth seeing.
            Log.warn("jk: action-cache record not published — a reader held the name", "key", key, "task", task);
        }
        offer(record);
        return record;
    }

    /**
     * Tell the running step it ran and stored under {@code key}, with why: read before the store
     * replaces anything, from the record already under {@code key} and the one the task's pointer
     * names, the same comparison {@code jk explain} makes.
     */
    private void noteStored(String task, String key, Map<String, String> inputs, long bytes) {
        StepCache.Reason reason;
        String changed = null;
        try {
            Optional<ActionRecord> same = lookupLocal(key, ToolchainPolicy.of(key));
            Optional<ActionRecord> previous = previousRecord(task);
            if (SessionContext.current().config().forceOr(false)
                    || SessionContext.current().config().rebuildOr(false)) {
                reason = StepCache.Reason.FORCED;
            } else if (same.isPresent()) {
                reason = local.missing(same.get().outputs().values()).isEmpty()
                        ? StepCache.Reason.FORCED
                        : StepCache.Reason.OUTPUTS_GONE;
            } else if (StepCacheNotes.sawRefusal()) {
                reason = StepCache.Reason.RECORD_REFUSED;
            } else if (remote != null && !remote.reachable()) {
                reason = StepCache.Reason.REMOTE_UNREACHABLE;
            } else if (previous.isEmpty()) {
                reason = StepCache.Reason.NO_RECORD;
            } else {
                List<String> moved = ChangedInputs.of(previous.get().inputs(), inputs);
                if (moved.isEmpty() && madeByAnotherJk(previous.get())) {
                    reason = StepCache.Reason.TOOLCHAIN_CHANGED;
                    changed = ActionKey.JK_INPUT;
                } else if (moved.isEmpty()) {
                    reason = StepCache.Reason.KEY_CHANGED;
                } else {
                    reason = moved.stream().allMatch(ActionCache::toolchainInput)
                            ? StepCache.Reason.TOOLCHAIN_CHANGED
                            : StepCache.Reason.INPUTS_CHANGED;
                    changed = String.join(", ", moved.subList(0, Math.min(MAX_CHANGED_NAMED, moved.size())))
                            + (moved.size() > MAX_CHANGED_NAMED ? ", +" + (moved.size() - MAX_CHANGED_NAMED) : "");
                }
            }
        } catch (IOException | RuntimeException e) {
            reason = StepCache.Reason.KEY_CHANGED;
        }
        StepCacheNotes.stored(key, bytes, reason, changed);
    }

    /** The running step's own time so far, which a record states as the cost of making it; {@code -1} outside a step. */
    private static long workedMillis() {
        TaskContext ctx = StepScope.current();
        return ctx == null ? -1 : ctx.workedMillis();
    }

    /** How many changed inputs a miss names before it counts the rest. */
    private static final int MAX_CHANGED_NAMED = 5;

    /**
     * Whether the record task {@code taskId}'s pointer names on this disk was made by another jk,
     * which its key hashes beside its inputs: a step whose inputs are all as they were misses only
     * for that. False with no such record, or one whose inputs name the jk themselves ({@link
     * ActionKey#JK_INPUT}), which says so by its inputs.
     */
    public boolean madeByAnotherJk(String taskId) {
        try {
            Optional<ActionRecord> previous = previousRecord(taskId);
            return previous.isPresent() && madeByAnotherJk(previous.get());
        } catch (IOException | RuntimeException unread) {
            return false;
        }
    }

    private static boolean madeByAnotherJk(ActionRecord record) {
        String made = record.meta().producer();
        return made != null && !record.inputs().containsKey(ActionKey.JK_INPUT) && !made.equals(producer());
    }

    /** The record the task's pointer names on this disk, read without telling the step. */
    private Optional<ActionRecord> previousRecord(String task) throws IOException {
        String key;
        try {
            key = AtomicWrites.readString(tasksDir().resolve(task)).trim();
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        return key.isEmpty() ? Optional.empty() : lookupLocal(key, ToolchainPolicy.of(key));
    }

    /** An input naming the compiler, JDK or jk rather than a source, dependency or setting of the build's own. */
    private static boolean toolchainInput(String name) {
        return name.equals("jdk") || name.equals("jk") || name.equals("worker") || name.startsWith("worker:");
    }

    /** Hand {@code record} to the build's remote tier, which uploads what may leave the machine behind the build. */
    private void offer(ActionRecord record) {
        if (remote != null) remote.offer(record, local::blobPath);
    }

    /**
     * Keep at most {@link HeavyActionPolicy#generations(String)} action keys of a Class-C task for
     * the checkout that wrote {@code outputRoot}, {@code newKey} included; older key files of that
     * checkout are deleted so {@link CasSweep} can reclaim their blobs. Per checkout rather than by
     * task-pointer flips: the pointer is shared by every checkout of the project, so two worktrees
     * on different branches would otherwise evict each other's native image or OCI tarball on
     * every alternate build. A key another checkout's generations still name is left alone. {@code
     * flipPointer} runs under the same lock, so the pointer and the list agree when two engines
     * store one task at once.
     */
    private void trimGenerations(String taskId, String newKey, Path outputRoot, FileLocks.Action flipPointer)
            throws IOException {
        int keep = HeavyActionPolicy.generations(taskId);
        String checkout = ActionKey.checkoutTag(outputRoot);
        Path gens = HeavyActionPolicy.gensFile(tasksDir(), taskId);
        FileLocks.withLock(HeavyActionPolicy.gensLock(gens), () -> {
            flipPointer.run();
            List<HeavyActionPolicy.Generation> mine = new ArrayList<>();
            List<HeavyActionPolicy.Generation> others = new ArrayList<>();
            mine.add(new HeavyActionPolicy.Generation(checkout, newKey));
            for (HeavyActionPolicy.Generation g : HeavyActionPolicy.readGenerations(gens)) {
                if (!g.checkout().equals(checkout)) others.add(g);
                else if (!g.key().equals(newKey)) mine.add(g);
            }
            int retained = Math.min(keep, mine.size());
            for (HeavyActionPolicy.Generation dropped : mine.subList(retained, mine.size())) {
                boolean namedElsewhere = others.stream().anyMatch(o -> o.key().equals(dropped.key()));
                if (!namedElsewhere) local.discard(dropped.key());
            }
            List<HeavyActionPolicy.Generation> kept = new ArrayList<>(others);
            kept.addAll(mine.subList(0, retained));
            HeavyActionPolicy.writeGenerations(gens, kept);
        });
    }

    /**
     * Clear the contents of {@code outputDir} and copy each cached output back from the CAS,
     * verifying every copied blob against its recorded digest. Stale files from a prior compile
     * are removed before restoring. Returns {@code false} — with {@code outputDir} holding only
     * the resource copies and merged classes {@link MirroredOutputs} records — when a blob is
     * missing or corrupt (the corrupt blob is dropped so the next store re-puts
     * it), or an output cannot be written (a full disk), so the caller falls through to a real run
     * instead of building on a short or wrong tree.
     */
    public boolean restore(ActionRecord record, Path outputDir) throws IOException {
        return WorkCounters.restored(counted(record, restoreInto(record, outputDir)));
    }

    private boolean restoreInto(ActionRecord record, Path outputDir) throws IOException {
        // Paths the resource mirror or a language merge owns stay on disk and are not rewritten
        // from this record: a compile that snapshotted them would otherwise delete or resurrect a
        // resource, or lay a dropped Kotlin class back beside javac's output. A main resource copy
        // the record holds is one a processor rewrote, and comes back with the rest.
        Set<String> mirrored = MirroredOutputs.recorded(outputDir);
        Set<String> skipped = mirrored.isEmpty() ? Set.of() : MirroredOutputs.notRestored(outputDir);
        Map<String, String> outputs = record.outputs();
        if (!skipped.isEmpty()) {
            Map<String, String> kept = new LinkedHashMap<>();
            for (var entry : outputs.entrySet()) {
                if (!skipped.contains(entry.getKey())) kept.put(entry.getKey(), entry.getValue());
            }
            outputs = kept;
        }
        if (!inside(outputDir, outputs.keySet())) return false;
        Optional<Map<String, CachedBlob>> resolved = resolveAll(outputs);
        boolean fetched = resolved.isEmpty() && fetchFromRemote(record);
        if (fetched) resolved = resolveAll(outputs);
        if (resolved.isEmpty()) return false;
        Map<String, CachedBlob> blobs = resolved.get();
        // Build-host compile freshness stamps (.jstamp/.kstamp) live inside the classes tree
        // but are not cached compiled output — write-stamp writes them after compile. They are
        // owned here so prune keeps them. Prune rather than wipe: FreshnessStamp compares
        // classpath entries by mtime, so re-copying an unchanged classes tree would invalidate
        // every downstream stamp on a cache hit.
        Set<Path> laidByOthers = new HashSet<>();
        for (String rel : mirrored) {
            laidByOthers.add(outputDir.resolve(rel).normalize());
        }
        Set<Path> owned = new HashSet<>(laidByOthers);
        for (String rel : outputs.keySet()) {
            owned.add(outputDir.resolve(rel).normalize());
        }
        for (String f : BuildStamps.ALL) {
            owned.add(outputDir.resolve(f).normalize());
        }
        if (Files.exists(outputDir)) {
            pruneUnowned(outputDir, owned);
        }
        Files.createDirectories(outputDir);
        meter(blobs, outputs); // cache hit: these bytes come back out of the cache
        for (Map.Entry<String, String> entry : outputs.entrySet()) {
            Path target = outputDir.resolve(entry.getKey());
            // Leave a byte-identical target alone — the mtime it keeps is what stops the KSP/Kotlin
            // thrash described above. Digest while copying otherwise: the memo seed below asserts
            // these exact bytes, so a truncated/corrupt blob must surface as a miss here, not as
            // green tests over wrong classes downstream.
            CachedBlob blob = Objects.requireNonNull(blobs.get(entry.getValue()));
            boolean laid;
            try {
                laid = layDown(blob, target, entry.getValue());
            } catch (IOException failedWrite) {
                // A full disk or a blocked path: what is on disk is not the record's tree.
                Log.warn("jk: action-cache restore cut short", "path", target, "cause", failedWrite.toString());
                laid = false;
            }
            if (!laid) {
                // A partial restore goes, all but what the resource mirror and the merges laid
                // there (less a half-written target): the compile that follows writes beside them.
                laidByOthers.remove(target.normalize());
                pruneUnowned(outputDir, laidByOthers);
                Files.createDirectories(outputDir);
                return false;
            }
            // CAS blobs carry no mode; the record does. Same reason as restoreArtifacts.
            if (record.executables().contains(entry.getKey())) {
                target.toFile().setExecutable(true, false);
            }
            // Seed content memo so TestStamp / package keys do not re-hash the whole tree.
            FileHashMemo.rememberContent(target, entry.getValue());
        }
        noteRestored(record, fetched || RemoteTier.adoptedThisBuild(remote, record.actionKey()), blobs);
        return true;
    }

    /** Tell the running step it restored {@code record}, from the remote tier when {@code fetched}. */
    private static void noteRestored(ActionRecord record, boolean fetched, Map<String, CachedBlob> blobs) {
        if (!StepCacheNotes.inStep() || record.actionKey() == null) return;
        long bytes = 0;
        for (CachedBlob b : blobs.values()) bytes += b.size();
        StepCacheNotes.restored(
                record.actionKey(), fetched, bytes, record.meta().wallMillis());
    }

    /**
     * Put {@code blob} at {@code target} unless a byte-identical file is already there. False when
     * the blob vanished, or when the copied bytes do not hash to {@code sha}: that blob is dropped
     * so the next store re-puts it.
     */
    private boolean layDown(CachedBlob blob, Path target, String sha) throws IOException {
        Files.createDirectories(target.getParent());
        if (blob.identicalAt(target, sha)) return true;
        Files.deleteIfExists(target);
        return switch (blob.copyTo(target, sha)) {
            case LAID -> true;
            case VANISHED -> false;
            case CORRUPT -> {
                dropCorruptBlob(blob);
                yield false;
            }
        };
    }

    /** A blob whose bytes no longer match its name is garbage — drop it so the next store re-puts. */
    private static void dropCorruptBlob(CachedBlob blob) {
        try {
            Files.deleteIfExists(blob.path());
        } catch (IOException ignored) {
            // best-effort hygiene
        }
    }

    /**
     * Restore recorded outputs by copying them into {@code baseDir} WITHOUT clearing it first
     * for single/few-file artifact tasks (jars, fat-jars, native binaries) whose output dir ({@code
     * target/}) holds unrelated files. Overwrites a stale artifact already at the path. Returns
     * {@code false} if any cached blob is missing, or if an output cannot be laid down whole — then
     * with none of the record's outputs left behind — so the caller rebuilds.
     */
    public boolean restoreArtifacts(ActionRecord record, Path baseDir) throws IOException {
        return restoreArtifacts(record, baseDir, true);
    }

    /**
     * As {@link #restoreArtifacts(ActionRecord, Path)}, writing the recorded files and nothing else:
     * for outputs that sit among sources (a generated file in {@code src/}), where the directory
     * they share is not the task's to clear.
     */
    public boolean restoreFiles(ActionRecord record, Path baseDir) throws IOException {
        return restoreArtifacts(record, baseDir, false);
    }

    private boolean restoreArtifacts(ActionRecord record, Path baseDir, boolean pruneRoots) throws IOException {
        return WorkCounters.restored(counted(record, copyArtifacts(record, baseDir, pruneRoots)));
    }

    private boolean copyArtifacts(ActionRecord record, Path baseDir, boolean pruneRoots) throws IOException {
        if (record.outputs().isEmpty()) return false;
        if (!inside(baseDir, record.outputs().keySet())) return false;
        Optional<Map<String, CachedBlob>> resolved = resolveAll(record.outputs());
        boolean fetched = resolved.isEmpty() && fetchFromRemote(record);
        if (fetched) resolved = resolveAll(record.outputs());
        if (resolved.isEmpty()) return false;
        Map<String, CachedBlob> blobs = resolved.get();
        // Clear the DIRECTORY roots this record owns before copyinga multi-file
        // layout (quarkus fast-jar lib/ app/ quarkus-app/) restored over a dirty target/
        // otherwise keeps stale extras beside the restored set — real packager runs clean
        // up, restores must too. Top-level FILE outputs are handled per-file below.
        Set<String> dirRoots = new TreeSet<>();
        for (String rel : record.outputs().keySet()) {
            int slash = rel.indexOf('/');
            if (slash > 0) dirRoots.add(rel.substring(0, slash));
        }
        // Prune rather than delete the root outright. The recorded outputs are exactly the files
        // about to be restored, so deleting one guarantees the byte-identical check below misses and
        // re-copies it with a fresh mtime — which is the precise churn removed, since
        // FreshnessStamp compares classpath entries by mtime. Dropping only the files this record
        // does NOT own clears stale extras just as well and leaves the unchanged ones alone.
        if (pruneRoots && !dirRoots.isEmpty()) {
            Set<Path> owned = new HashSet<>();
            for (String rel : record.outputs().keySet()) {
                owned.add(baseDir.resolve(rel).normalize());
            }
            for (String root : dirRoots) {
                pruneUnowned(baseDir.resolve(root), owned);
            }
        }
        meter(blobs, record.outputs());
        for (Map.Entry<String, String> e : record.outputs().entrySet()) {
            Path target = baseDir.resolve(e.getKey());
            // Leave a byte-identical target alone. Re-copying it is not merely wasted I/O: it
            // bumps the file's mtime, and FreshnessStamp compares classpath entries by mtime — so
            // restoring an unchanged sibling jar invalidated every downstream stamp and forced a
            // full KSP round (and Kotlin recompile) on every single build. Blobs may live in the
            // store CAS after a release promote (Class-C).
            CachedBlob blob = Objects.requireNonNull(blobs.get(e.getValue()));
            boolean laid;
            try {
                laid = layDown(blob, target, e.getValue());
            } catch (IOException failedWrite) {
                Log.warn("jk: action-cache restore cut short", "path", target, "cause", failedWrite.toString());
                laid = false;
            }
            if (!laid) {
                // A short restore is a miss, and leaves none of the record's outputs behind: the
                // caller re-runs the action over a target that holds nothing it half-restored.
                discardOutputs(record, baseDir);
                return false;
            }
            // CAS blobs carry no mode; the record does. Applied on every restore, not only the
            // copying branch — a byte-identical target left alone may still have lost the bit.
            if (record.executables().contains(e.getKey())) {
                target.toFile().setExecutable(true, false);
            }
            // Known CAS digest — seed so later ClasspathFingerprint/TestStamp work is free.
            FileHashMemo.rememberContent(target, e.getValue());
        }
        noteRestored(record, fetched || RemoteTier.adoptedThisBuild(remote, record.actionKey()), blobs);
        return true;
    }

    /**
     * True when every path of {@code rels} is a plain relative path ({@link
     * ActionRecordText#isSafeRelative}) that resolves inside {@code dir}. A parsed record already
     * holds only such paths; this also holds a record built in memory to it before a restore writes
     * or prunes anything.
     */
    static boolean inside(Path dir, Set<String> rels) {
        Path root = dir.toAbsolutePath().normalize();
        for (String rel : rels) {
            if (!ActionRecordText.isSafeRelative(rel)) return false;
            Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root) || target.equals(root)) return false;
        }
        return true;
    }

    /** Best-effort removal of every output {@code record} names under {@code baseDir}. */
    private static void discardOutputs(ActionRecord record, Path baseDir) {
        for (String rel : record.outputs().keySet()) {
            try {
                Files.deleteIfExists(baseDir.resolve(rel));
            } catch (IOException ignored) {
                // a path a directory now occupies, or one the disk will not let go of
            }
        }
    }

    /**
     * Delete every file under {@code dir} that {@code owned} does not name, then any directory left
     * empty — so a restore clears stale extras without disturbing the outputs it is about
     * to restore. The temp file of a write in flight to an owned file is kept: a stamp another step
     * is writing this moment would otherwise lose its temp before the rename. One walk, each
     * directory listed once.
     */
    private static void pruneUnowned(Path dir, Set<Path> owned) throws IOException {
        PathUtil.pruneTree(dir, p -> owned.contains(p.normalize()) || owned.contains(AtomicWrites.inFlightTarget(p)));
    }

    /**
     * CAS-store already-produced {@code artifacts} (copying each into the CAS) and write an
     * {@link ActionRecord} keyed by {@code actionKey}, with each artifact's {@code baseDir}-relative
     * path as its output key. The companion of {@link #restoreArtifacts} for single/few-file
     * packaging.
     *
     * <p>Refuses an empty output set. {@link #restoreArtifacts} treats empty outputs as a miss, so
     * persisting one would look like a hit and then rebuild anyway. Refuses an artifact that is not
     * on disk too: the step did not write everything it names, and a record of the rest would
     * restore a short set under a key that promises all of it.
     */
    public ActionRecord storeArtifacts(
            @Nullable String taskId,
            @Nullable String actionKey,
            Map<String, String> inputs,
            @Nullable Path baseDir,
            List<Path> artifacts)
            throws IOException {
        return storeArtifacts(taskId, actionKey, inputs, baseDir, artifacts, List.of());
    }

    /** As {@link #storeArtifacts(String, String, Map, Path, List)}, keeping what the action {@linkplain ActionRecord#said() said}. */
    public ActionRecord storeArtifacts(
            @Nullable String taskId,
            @Nullable String actionKey,
            Map<String, String> inputs,
            @Nullable Path baseDir,
            List<Path> artifacts,
            List<String> said)
            throws IOException {
        // Artifacts are recorded relative to the output root they were written under.
        Path root = Objects.requireNonNull(baseDir, "baseDir");
        Map<String, String> outputs = new TreeMap<>();
        Set<String> executables = new TreeSet<>();
        if (!DiskGuard.admits(this.root, artifacts)) return new ActionRecord(taskId, actionKey, inputs, Map.of());
        for (Path a : artifacts) {
            if (!Files.isRegularFile(a)) {
                throw new IOException("not caching an action whose output was not written: " + a);
            }
            // Memo, and seed it — see store.
            String hex = FileHashMemo.contentHash(a);
            local.putBlob(hex, a); // never link a mutable target/ artifact into the CAS
            FileHashMemo.rememberContent(a, hex);

            String rel = root.relativize(a).toString().replace(File.separatorChar, '/');
            outputs.put(rel, hex);
            if (executableBit(a)) executables.add(rel);
        }
        if (outputs.isEmpty()) {
            throw new IOException("packaging produced no files to cache: " + artifacts);
        }
        return storeWithOutputs(
                ActionRecordText.Writes.OUTPUTS, taskId, actionKey, inputs, outputs, executables, root, said);
    }

    /**
     * Fold one action's output bytes into the run's ledger: {@code intoCache} for a store, else a
     * restore. Sizes come off the CAS blobs at rest — a stat per output, nothing wrapped around the
     * copy — and land as the run's {@code local} traffic on the dashboard.
     */
    /** {@code restored}, counted as a hit in {@code record}'s tier when it is true. */
    private boolean counted(ActionRecord record, boolean restored) {
        if (!restored) return false;
        String task = record.taskId() == null ? "" : record.taskId();
        long bytes = 0;
        for (String sha : record.outputs().values()) {
            if (isSha256Hex(sha)) bytes += blobSize(sha);
        }
        CacheStats.hit(CacheStats.classify(task, record.outputs(), this::blobSize), bytes);
        return true;
    }

    /** Bytes of blob {@code sha} in either CAS, 0 when it is in neither. */
    private long blobSize(String sha) {
        return isSha256Hex(sha) ? IoLedger.sizeOf(resolveBlob(sha)) : 0L;
    }

    /**
     * Each output's blob size, by output path, off the CAS at rest. Not every record's values are
     * blob hashes: a marker record (run-tests) parks small scalars here instead, and has no size.
     */
    private Map<String, Long> outputSizes(Map<String, String> outputs) {
        if (outputs.isEmpty()) return Map.of();
        Map<String, Long> sizes = new TreeMap<>();
        for (var e : outputs.entrySet()) {
            if (isSha256Hex(e.getValue())) sizes.put(e.getKey(), IoLedger.sizeOf(resolveBlob(e.getValue())));
        }
        return sizes;
    }

    /**
     * The jk a record names as its producer: the cache-key version and every {@link KeyContract}'s
     * digest, which between them are all a key hashes of jk itself. A record does not say which
     * contract keyed it, so a miss under a moved contract reads as made by another jk for every
     * family; only a miss consults it.
     */
    static String producer() {
        StringBuilder made = new StringBuilder("jk/").append(BuildIdentity.cacheKeyVersion());
        for (KeyContract contract : KeyContract.values()) made.append('+').append(contract.digest());
        return made.toString();
    }

    /**
     * Metering from sizes already in hand — a restore has resolved every blob before it copies, and
     * asking the filesystem again for a number it just read is a third of that path's stats.
     */
    private static void meter(Map<String, CachedBlob> blobs, Map<String, String> outputs) {
        if (outputs.isEmpty()) return;
        long bytes = 0;
        for (String sha : outputs.values()) {
            CachedBlob b = blobs.get(sha);
            if (b != null) bytes += b.size();
        }
        SessionContext.current().io().localDown(bytes);
    }

    /** True for a 64-char lowercase-or-uppercase hex string — the shape {@link Cas} keys blobs by. */
    private static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    // --- record + serialization --------------------------------------------

    /**
     * One cached action: the task pointer it was stored under, its key, the inputs the key hashed
     * (spelled as {@link ActionKey#snapshotInputs} spells them, so the record reads the same from
     * any checkout), and each output's relative path to its CAS digest.
     */
    public record ActionRecord(
            @Nullable String taskId,
            @Nullable String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            /**
             * Output rel-paths that were executable when stored. CAS blobs carry no mode, so
             * without this a restored binary comes back 0644 and is unrunnable. Empty means no
             * extra execute bits (default 0644 on restore).
             */
            Set<String> executables,
            /**
             * What the action told the user as it ran — a plugin step's diagnostic replies, as the
             * worker sent them — in order, for a hit to say again: a restored action says what
             * its run said. Empty for an action that said nothing, and for a record written
             * before records kept it.
             */
            List<String> said,
            /**
             * What the record says about itself: when, by which jk and on which platform it was
             * made, and each blob output's size. {@link ActionRecordText.Meta#NONE} for a record
             * built in memory.
             */
            ActionRecordText.Meta meta) {

        public ActionRecord {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(actionKey, "actionKey");
            inputs = Map.copyOf(inputs);
            outputs = Map.copyOf(outputs);
            executables = executables == null ? Set.of() : Set.copyOf(executables);
            said = said == null ? List.of() : List.copyOf(said);
            meta = meta == null ? ActionRecordText.Meta.NONE : meta;
        }

        public ActionRecord(
                @Nullable String taskId,
                @Nullable String actionKey,
                Map<String, String> inputs,
                Map<String, String> outputs,
                Set<String> executables,
                List<String> said) {
            this(taskId, actionKey, inputs, outputs, executables, said, ActionRecordText.Meta.NONE);
        }

        /** The bytes of every distinct blob this record names, from its recorded sizes. */
        public long totalBytes() {
            return ActionRecordText.totalBytes(outputs, meta.sizes());
        }

        public ActionRecord(
                @Nullable String taskId,
                @Nullable String actionKey,
                Map<String, String> inputs,
                Map<String, String> outputs,
                Set<String> executables) {
            this(taskId, actionKey, inputs, outputs, executables, List.of());
        }

        public ActionRecord(
                @Nullable String taskId,
                @Nullable String actionKey,
                Map<String, String> inputs,
                Map<String, String> outputs) {
            this(taskId, actionKey, inputs, outputs, Set.of());
        }
    }

    private static String render(ActionRecord record) {
        return ActionRecordText.render(
                Objects.requireNonNull(record.taskId()),
                Objects.requireNonNull(record.actionKey()),
                record.inputs(),
                record.outputs(),
                record.executables(),
                record.said(),
                record.meta());
    }

    private static ActionRecord parse(String content, String actionKey) {
        ActionRecordText.Parsed p = ActionRecordText.parse(content, actionKey);
        return new ActionRecord(
                p.taskId(), p.actionKey(), p.inputs(), p.outputs(), p.executables(), p.said(), p.meta());
    }

    private Path tasksDir() {
        return ActionTree.TASKS.under(root);
    }
}
