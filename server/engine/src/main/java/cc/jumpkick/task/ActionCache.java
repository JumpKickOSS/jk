// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    }

    /**
     * CAS pool this action cache stores/restores payloads in — must be the <em>cache</em> CAS
     * ({@link cc.jumpkick.cache.JkStores#cacheCas}), never the artifact store.
     */
    public Cas cas() {
        return cas;
    }

    /**
     * Resolve every blob in {@code outputs} once, with its size.
     *
     * <p>Presence, {@link #meter}, and the copy share this map so each blob is {@code readAttributes}
     * once. Empty when any blob is missing: a restore cannot proceed without all of them.
     */
    private Optional<Map<String, Blob>> resolveAll(Map<String, String> outputs) {
        Map<String, Blob> blobs = new HashMap<>();
        for (String sha : outputs.values()) {
            if (blobs.containsKey(sha)) continue;
            Blob b = blob(sha);
            if (b == null) return Optional.empty();
            blobs.put(sha, b);
        }
        return Optional.of(blobs);
    }

    /** A resolved blob: where it is, and how big, from one {@code readAttributes}. */
    private record Blob(Path path, long size) {}

    /**
     * {@code sha}'s blob in the cache CAS, else the store CAS (promoted Class-C), else {@code null}.
     * One {@code readAttributes} per candidate answers existence, regular-file-ness, and size.
     */
    private @Nullable Blob blob(String sha) {
        Blob hit = statBlob(cas.pathFor(sha));
        if (hit != null) return hit;
        return storeCas == null ? null : statBlob(storeCas.pathFor(sha));
    }

    private static @Nullable Blob statBlob(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            return a.isRegularFile() ? new Blob(p, a.size()) : null;
        } catch (IOException absent) {
            return null;
        }
    }

    /** Resolve a blob path: cache CAS first, then store CAS (promoted Class-C). */
    Path resolveBlob(String sha) {
        Path p = cas.pathFor(sha);
        if (Files.isRegularFile(p)) return p;
        if (storeCas != null) {
            Path s = storeCas.pathFor(sha);
            if (Files.isRegularFile(s)) return s;
        }
        return p;
    }

    private boolean hasBlob(String sha) {
        return Files.isRegularFile(resolveBlob(sha));
    }

    /**
     * The record for {@code actionKey}, or empty when there is none. Reads without a prior
     * existence check: another engine's {@link ActionCachePrune} can unlink the key between the two,
     * and a vanished entry is a miss, not a failure.
     */
    public Optional<ActionRecord> lookup(String actionKey) throws IOException {
        // A blank key (a torn tasks/ pointer) names the keys dir itself, not a record.
        if (actionKey.isBlank()) return Optional.empty();
        Path key = keysDir().resolve(actionKey);
        String content;
        try {
            content = AtomicWrites.readString(key);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException denied) {
            if (!deniedByAConcurrentReplace(denied)) throw denied;
            return Optional.empty();
        }
        ActionRecord record;
        try {
            record = parse(content);
        } catch (RuntimeException torn) {
            // A record cut short — an engine killed mid-write, a full disk — is a miss, not a
            // failure of every step that consults this key on every build until --rebuild. The
            // file goes so the next real run's store is what the key names.
            Files.deleteIfExists(key);
            return Optional.empty();
        }
        stampUsed(key);
        return Optional.of(record);
    }

    /**
     * Stamp {@code key} as used, so the prune ranks by last use rather than by last store.
     *
     * <p>A cache hit rewrites nothing else: the CAS is write-once and only a real run replaces the
     * record. Without this stamp a module nobody edits keeps one ancient key and sorts *ahead* of
     * the week-old dead keys of a module rebuilt hourly — the prune would evict what you always use
     * and keep the debris.
     *
     * <p>Best-effort and coarsened: a read-only cache root, or a key a concurrent prune just took,
     * simply keeps its previous ranking.
     */
    private static void stampUsed(Path key) {
        long now = System.currentTimeMillis();
        Long last = STAMPED.get(key);
        if (last != null && now - last < STAMP_COARSENING_MILLIS) return;
        try {
            Files.setLastModifiedTime(key, FileTime.fromMillis(now));
            STAMPED.put(key, now);
        } catch (IOException ignored) {
            // Ranking hint, never correctness.
        }
    }

    /**
     * Keys this engine stamped recently. A build looks the same entry up several times (forecast,
     * plan, restore), and a resident engine repeats that every build; without the map the stamp
     * would be one write per lookup instead of one per entry per hour.
     */
    private static final Map<Path, Long> STAMPED = new ConcurrentHashMap<>();

    /** How coarse the last-use stamp is. Eviction ranks in days; sub-hour precision buys nothing. */
    private static final long STAMP_COARSENING_MILLIS = Duration.ofHours(1).toMillis();

    /** Drop the stamp memo at the idle boundary, alongside the other per-build heap residue. */
    public static void clearStampCache() {
        STAMPED.clear();
    }

    /** The record the {@code tasks/} pointer for {@code taskId} names, or empty. Same race, same answer. */
    public Optional<ActionRecord> lastFor(String taskId) throws IOException {
        String actionKey;
        try {
            actionKey = AtomicWrites.readString(tasksDir().resolve(taskId)).trim();
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException denied) {
            if (!deniedByAConcurrentReplace(denied)) throw denied;
            return Optional.empty();
        }
        return lookup(actionKey);
    }

    /**
     * Whether {@code e} is Windows refusing a read of a name a concurrent store is replacing,
     * after {@link AtomicWrites#readString} has already spent its retry budget on it.
     *
     * <p>True makes the lookup a miss. That is always safe and never a wrong hit — a miss costs
     * the caller a re-run of an action whose record it could not read — where throwing costs the
     * build a step for a race it did not cause. It is the read-side mirror of the store's own
     * decision not to fail a step whose outputs are already in the CAS.
     *
     * <p>Warned rather than debugged, for the same reason the store side warns: with the budget
     * {@link AtomicWrites} uses, reaching here means the contention outran a measurement that saw
     * none, and a cache quietly missing is the kind of thing that reads as "jk got slower".
     */
    private static boolean deniedByAConcurrentReplace(IOException e) {
        if (!Os.isWindows() || !AtomicWrites.isTransientWindowsLock(e)) return false;
        Log.warn("jk: action-cache entry not read — a writer held the name", "path", String.valueOf(e.getMessage()));
        return true;
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
        Map<String, String> outputs = new TreeMap<>();
        Set<String> executables = new TreeSet<>();
        if (Files.exists(outputDir)) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> stream = Files.find(outputDir, Integer.MAX_VALUE, (p, attrs) -> attrs.isRegularFile())) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    // FreshnessStamp's sentinels (.jstamp/.kstamp) live inside
                    // outputDir but aren't action outputs — exclude them so we
                    // don't accidentally cache a stamp from a previous run.
                    if (BuildStamps.isStampFile(file.getFileName().toString())) continue;
                    // `.jk-*` scratch (a plugin's private bootstrap repo/staging — the
                    // plugin-sdk copyTree convention) is never an action output.
                    if (hasJkScratchSegment(outputDir.relativize(file))) continue;
                    files.add(file);
                }
            }
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
        return storeWithOutputs(taskId, actionKey, inputs, outputs, executables, outputDir);
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
     * <p>{@link JkThreads#io()} rather than {@code cpu()}: this waits on the filesystem, and the CPU
     * pool is what the compile lanes want.
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
                pending.add(JkThreads.io().submit(() -> {
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
        // Through the memo, not Hashing directly: on a warm rebuild this answers from
        // the map and the output is never read at all, where the old direct hash read
        // every one of them (6,733 class files in this checkout). putFile's own exists()
        // then skips the copy for a blob already present, so a warm store costs one stat
        // per output.
        String hex = FileHashMemo.contentHash(file);
        cas.putFile(file, hex);
        // Seed the memo with the digest we just established. Without this every
        // downstream ClasspathFingerprint re-hashed the whole tree from cold, because
        // contentHash refuses to record a file written inside its settle window — which
        // is exactly what rememberContent exists to bypass, and it was only ever called
        // on the restore paths.
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

    /** True when any path segment starts with {@code .jk-} — plugin-private scratch, never cached. */
    static boolean hasJkScratchSegment(Path rel) {
        for (Path seg : rel) {
            if (seg.toString().startsWith(".jk-")) return true;
        }
        return false;
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
        return storeWithOutputs(taskId, actionKey, inputs, Map.of());
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
        return storeWithOutputs(taskId, actionKey, inputs, outputs, Set.of(), null);
    }

    /**
     * The store every path funnels through: the record, then the task pointer, then the Class-C
     * generation trim. {@code executables} are the outputs a restore gives the bit back to; {@code
     * outputRoot} is the tree the outputs were written under, which names the checkout a Class-C
     * generation belongs to.
     */
    private ActionRecord storeWithOutputs(
            @Nullable String taskId,
            @Nullable String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Set<String> executables,
            @Nullable Path outputRoot)
            throws IOException {
        Files.createDirectories(keysDir());
        Files.createDirectories(tasksDir());
        meter(outputs, true); // every store path funnels here — one place to count cache-in bytes
        String task = Objects.requireNonNull(taskId, "taskId");
        String key = Objects.requireNonNull(actionKey, "actionKey");
        ActionRecord record = new ActionRecord(task, key, inputs, outputs, executables);
        // Atomic temp+move: concurrent store/lookup under cacheGate read mode must never see a
        // truncated keys/ or tasks/ file. Order preserved: key before task pointer.
        try {
            AtomicWrites.replace(keysDir().resolve(key), render(record));
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
        return record;
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
                if (!namedElsewhere) Files.deleteIfExists(keysDir().resolve(dropped.key()));
            }
            List<HeavyActionPolicy.Generation> kept = new ArrayList<>(others);
            kept.addAll(mine.subList(0, retained));
            HeavyActionPolicy.writeGenerations(gens, kept);
        });
    }

    /**
     * Clear the contents of {@code outputDir} and copy each cached output back from the CAS,
     * verifying every copied blob against its recorded digest. Stale files from a prior compile
     * are removed before restoring. Returns {@code false} — with {@code outputDir} left empty —
     * when a blob is missing or corrupt (the corrupt blob is dropped so the next store re-puts
     * it), so the caller falls through to a real run instead of building on wrong bytes.
     */
    public boolean restore(ActionRecord record, Path outputDir) throws IOException {
        Optional<Map<String, Blob>> resolved = resolveAll(record.outputs());
        if (resolved.isEmpty()) return false;
        Map<String, Blob> blobs = resolved.get();
        // Build-host compile freshness stamps (.jstamp/.kstamp) live inside the classes tree
        // but are not cached compiled output — write-stamp writes them after compile. They are
        // owned here so prune keeps them. Prune rather than wipe: FreshnessStamp compares
        // classpath entries by mtime, so re-copying an unchanged classes tree would invalidate
        // every downstream stamp on a cache hit.
        Set<Path> owned = new HashSet<>();
        for (String rel : record.outputs().keySet()) {
            owned.add(outputDir.resolve(rel).normalize());
        }
        for (String f : BuildStamps.ALL) {
            owned.add(outputDir.resolve(f).normalize());
        }
        if (Files.exists(outputDir)) {
            pruneUnowned(outputDir, owned);
        }
        Files.createDirectories(outputDir);
        meter(blobs, record.outputs()); // cache hit: these bytes come back out of the cache
        for (Map.Entry<String, String> entry : record.outputs().entrySet()) {
            Path target = outputDir.resolve(entry.getKey());
            // COPY, never link: compilers rewrite restored class files IN PLACE on the next
            // build, and a hard link would let that rewrite mutate the CAS blob (see
            // Cas.putFile). Costs O(bytes) instead of O(entries) — correctness wins.
            Files.createDirectories(target.getParent());
            // Leave a byte-identical target alone — the mtime it keeps is what stops the KSP/Kotlin
            // thrash described above. Digest while copying otherwise: the memo seed below asserts
            // these exact bytes, so a truncated/corrupt blob must surface as a miss here, not as
            // green tests over wrong classes downstream.
            if (!identicalTo(target, entry.getValue())) {
                Files.deleteIfExists(target);
                var blob = blobs.get(entry.getValue());
                if (blob == null || !copyVerified(blob.path(), target, entry.getValue())) {
                    dropCorruptBlob(entry.getValue());
                    // The contract is an empty outputDir on failure, so a partial restore is wiped
                    // rather than pruned.
                    deleteRecursively(outputDir);
                    Files.createDirectories(outputDir);
                    return false;
                }
            }
            // CAS blobs carry no mode; the record does. Same reason as restoreArtifacts.
            if (record.executables().contains(entry.getKey())) {
                target.toFile().setExecutable(true, false);
            }
            // Seed content memo so TestStamp / package keys do not re-hash the whole tree.
            FileHashMemo.rememberContent(target, entry.getValue());
        }
        return true;
    }

    /**
     * Copy {@code blob} to {@code target} computing SHA-256 on the way; true when the bytes match
     * {@code expectedSha}. A mismatching target is deleted before returning false. A blob that
     * disappeared after the caller's presence check is also false, not a throw.
     */
    private static boolean copyVerified(Path blob, Path target, String expectedSha) throws IOException {
        MessageDigest md = Hashing.newSha256();
        try (var in = Files.newInputStream(blob);
                var out = new DigestOutputStream(
                        Files.newOutputStream(
                                target,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE),
                        md)) {
            in.transferTo(out);
        } catch (NoSuchFileException vanished) {
            // Another process's prune unlinked the blob between the presence check and this open.
            // A missing blob is a cache miss, not a build failure — the caller re-runs the action.
            Files.deleteIfExists(target);
            return false;
        }
        if (expectedSha.equalsIgnoreCase(Hashing.hex(md.digest()))) {
            return true;
        }
        Files.deleteIfExists(target);
        return false;
    }

    /** A blob whose bytes no longer match its name is garbage — drop it so the next store re-puts. */
    private void dropCorruptBlob(String sha) {
        try {
            Files.deleteIfExists(resolveBlob(sha));
        } catch (IOException ignored) {
            // best-effort hygiene
        }
    }

    /**
     * Restore recorded outputs by copying them into {@code baseDir} WITHOUT clearing it first
     * for single/few-file artifact tasks (jars, fat-jars, native binaries) whose output dir ({@code
     * target/}) holds unrelated files. Overwrites a stale artifact already at the path. Returns
     * {@code false} (restoring nothing) if any cached blob is missing, so the caller rebuilds.
     */
    public boolean restoreArtifacts(ActionRecord record, Path baseDir) throws IOException {
        if (record.outputs().isEmpty()) return false;
        for (String sha : record.outputs().values()) {
            if (!hasBlob(sha)) return false;
        }
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
        if (!dirRoots.isEmpty()) {
            Set<Path> owned = new HashSet<>();
            for (String rel : record.outputs().keySet()) {
                owned.add(baseDir.resolve(rel).normalize());
            }
            for (String root : dirRoots) {
                pruneUnowned(baseDir.resolve(root), owned);
            }
        }
        meter(record.outputs(), false);
        for (Map.Entry<String, String> e : record.outputs().entrySet()) {
            Path target = baseDir.resolve(e.getKey());
            Files.createDirectories(target.getParent());
            // Leave a byte-identical target alone. Re-copying it is not merely wasted I/O: it
            // bumps the file's mtime, and FreshnessStamp compares classpath entries by mtime — so
            // restoring an unchanged sibling jar invalidated every downstream stamp and forced a
            // full KSP round (and Kotlin recompile) on every single build.
            if (!identicalTo(target, e.getValue())) {
                Files.deleteIfExists(target);
                // COPY, never link: a packager may later rewrite the target in place, and a link
                // would let that rewrite mutate the blob (see Cas.putFile). Digest while copying —
                // the memo seed below asserts these exact bytes. Blobs may live in the store CAS
                // after a release promote (Class-C).
                if (!copyVerified(resolveBlob(e.getValue()), target, e.getValue())) {
                    dropCorruptBlob(e.getValue());
                    return false;
                }
            }
            // CAS blobs carry no mode; the record does. Applied on every restore, not only the
            // copying branch — a byte-identical target left alone may still have lost the bit.
            if (record.executables().contains(e.getKey())) {
                target.toFile().setExecutable(true, false);
            }
            // Known CAS digest — seed so later ClasspathFingerprint/TestStamp work is free.
            FileHashMemo.rememberContent(target, e.getValue());
        }
        return true;
    }

    /**
     * Delete every file under {@code dir} that {@code owned} does not name, then any directory left
     * empty — so a restore clears stale extras without disturbing the outputs it is about
     * to restore. Deepest-first, so a directory is only tested once its children are gone.
     */
    private static void pruneUnowned(Path dir, Set<Path> owned) throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> deepestFirst;
        try (var walk = Files.walk(dir)) {
            deepestFirst = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : deepestFirst) {
            if (Files.isDirectory(p)) {
                if (!p.equals(dir) && isEmptyDir(p)) Files.deleteIfExists(p);
            } else if (!owned.contains(p.normalize())) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * True when {@code target} already holds exactly the CAS blob {@code sha}. Size is checked
     * first so the hash is only paid when it can actually match.
     */
    private boolean identicalTo(Path target, String sha) throws IOException {
        if (!Files.isRegularFile(target)) return false;
        Path blob = resolveBlob(sha);
        if (!Files.isRegularFile(blob)) return false;
        if (Files.size(target) != Files.size(blob)) return false;
        // The memo, not a full re-read: restoreArtifacts seeds this very file via rememberContent,
        // so the check that exists to avoid churning mtime costs a map lookup rather than re-reading
        // a fat jar on every warm build.
        return sha.equals(FileHashMemo.contentHash(target));
    }

    /**
     * CAS-store already-produced {@code artifacts} (copying each into the CAS) and write an
     * {@link ActionRecord} keyed by {@code actionKey}, with each artifact's {@code baseDir}-relative
     * path as its output key. The companion of {@link #restoreArtifacts} for single/few-file
     * packaging.
     *
     * <p>Refuses an empty output set. {@link #restoreArtifacts} treats empty outputs as a miss, so
     * persisting one would look like a hit and then rebuild anyway.
     */
    public ActionRecord storeArtifacts(
            @Nullable String taskId,
            @Nullable String actionKey,
            Map<String, String> inputs,
            @Nullable Path baseDir,
            List<Path> artifacts)
            throws IOException {
        // Artifacts are recorded relative to the output root they were written under.
        Path root = Objects.requireNonNull(baseDir, "baseDir");
        Map<String, String> outputs = new TreeMap<>();
        Set<String> executables = new TreeSet<>();
        for (Path a : artifacts) {
            if (!Files.isRegularFile(a)) continue;
            // Memo, and seed it — see store.
            String hex = FileHashMemo.contentHash(a);
            cas.putFile(a, hex); // never link a mutable target/ artifact into the CAS
            FileHashMemo.rememberContent(a, hex);

            String rel = root.relativize(a).toString().replace(File.separatorChar, '/');
            outputs.put(rel, hex);
            if (executableBit(a)) executables.add(rel);
        }
        if (outputs.isEmpty()) {
            throw new IOException("packaging produced no files to cache: " + artifacts);
        }
        return storeWithOutputs(taskId, actionKey, inputs, outputs, executables, root);
    }

    /**
     * Fold one action's output bytes into the run's ledger: {@code intoCache} for a store, else a
     * restore. Sizes come off the CAS blobs at rest — a stat per output, nothing wrapped around the
     * copy — and land as the run's {@code local} traffic on the dashboard.
     */
    private void meter(Map<String, String> outputs, boolean intoCache) {
        if (outputs.isEmpty()) return;
        long bytes = 0;
        for (String sha : outputs.values()) {
            // Not every record's values are blob hashes: a marker record (run-tests) parks small
            // scalars here instead, so meter only what is actually a CAS object.
            if (isSha256Hex(sha)) bytes += IoLedger.sizeOf(resolveBlob(sha));
        }
        IoLedger io = SessionContext.current().io();
        if (intoCache) io.localUp(bytes);
        else io.localDown(bytes);
    }

    /**
     * Metering from sizes already in hand — a restore has resolved every blob before it copies, and
     * asking the filesystem again for a number it just read is a third of that path's stats.
     */
    private static void meter(Map<String, Blob> blobs, Map<String, String> outputs) {
        if (outputs.isEmpty()) return;
        long bytes = 0;
        for (String sha : outputs.values()) {
            Blob b = blobs.get(sha);
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
            Set<String> executables) {

        public ActionRecord {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(actionKey, "actionKey");
            inputs = Map.copyOf(inputs);
            outputs = Map.copyOf(outputs);
            executables = executables == null ? Set.of() : Set.copyOf(executables);
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
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(record.taskId()).append('\n');
        sb.append("KEY ").append(record.actionKey()).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(record.inputs()).entrySet()) {
            sb.append("INPUT ")
                    .append(encodeValue(e.getValue()))
                    .append(' ')
                    .append(e.getKey())
                    .append('\n');
        }
        for (Map.Entry<String, String> e : new TreeMap<>(record.outputs()).entrySet()) {
            sb.append("OUTPUT ")
                    .append(encodeValue(e.getValue()))
                    .append(' ')
                    .append(e.getKey())
                    .append('\n');
        }
        // EXEC <relPath> — the output was executable when stored.
        for (String rel : new TreeSet<>(record.executables())) {
            sb.append("EXEC ").append(rel).append('\n');
        }
        return sb.toString();
    }

    /**
     * A record line is {@code INPUT <value> <key>} split at the first space, so a value carries no
     * space of its own: {@code %} and space are percent-encoded on the way out and decoded on the
     * way back. A content sha, the usual value, passes through untouched; a packaging token bag
     * whose args carry spaces round-trips instead of coming back as an empty map.
     */
    static String encodeValue(String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('%') < 0) return value;
        return value.replace("%", "%25").replace(" ", "%20");
    }

    static String decodeValue(String value) {
        if (value.indexOf('%') < 0) return value;
        return value.replace("%20", " ").replace("%25", "%");
    }

    private static ActionRecord parse(String content) {
        String taskId = null;
        String actionKey = null;
        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, String> outputs = new LinkedHashMap<>();
        Set<String> executables = new LinkedHashSet<>();
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            if (line.startsWith("TASK ")) {
                taskId = line.substring("TASK ".length()).trim();
            } else if (line.startsWith("KEY ")) {
                actionKey = line.substring("KEY ".length()).trim();
            } else if (line.startsWith("INPUT ")) {
                String body = line.substring("INPUT ".length());
                int sp = body.indexOf(' ');
                inputs.put(body.substring(sp + 1), decodeValue(body.substring(0, sp)));
            } else if (line.startsWith("OUTPUT ")) {
                String body = line.substring("OUTPUT ".length());
                int sp = body.indexOf(' ');
                outputs.put(body.substring(sp + 1), decodeValue(body.substring(0, sp)));
            } else if (line.startsWith("EXEC ")) {
                executables.add(line.substring("EXEC ".length()).trim());
            }
        }
        return new ActionRecord(
                Objects.requireNonNull(taskId, "taskId in record"),
                Objects.requireNonNull(actionKey, "actionKey in record"),
                inputs,
                outputs,
                executables);
    }

    private Path keysDir() {
        return ActionTree.KEYS.under(root);
    }

    private Path tasksDir() {
        return ActionTree.TASKS.under(root);
    }

    private static void deleteRecursively(Path target) throws IOException {
        PathUtil.deleteRecursivelyOrThrow(target);
    }
}
