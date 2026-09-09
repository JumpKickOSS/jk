// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.util.AtomicWrites;
import java.io.File;
import java.io.IOException;
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
 * Class-C tasks also keep a short generation list ({@code tasks/<taskId>.gens}).
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
        Path key = keysDir().resolve(actionKey);
        try {
            ActionRecord record = parse(Files.readString(key));
            stampUsed(key);
            return Optional.of(record);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
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
            actionKey = Files.readString(tasksDir().resolve(taskId)).trim();
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        return lookup(actionKey);
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
        // Refuse empty success records: a non-empty source set that produced zero classes
        // must not become a cache hit that restores an empty tree on the next build.
        if (outputs.isEmpty() && hasSourceInputs(inputs)) {
            return new ActionRecord(taskId, actionKey, inputs, Map.of(), Map.of());
        }
        return storeWithOutputs(taskId, actionKey, inputs, outputs, Map.of(), executables);
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

    /** True when {@code inputs} includes at least one source-file fingerprint (not only flags/cp). */
    static boolean hasSourceInputs(Map<String, String> inputs) {
        if (inputs == null || inputs.isEmpty()) return false;
        for (String k : inputs.keySet()) {
            if (k.startsWith("cp:") || k.startsWith("pp:")) continue;
            if (k.equals("release") || k.equals("options")) continue;
            return true; // absolute source path keys from ActionKey.snapshotInputs
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
     */
    public ActionRecord storeWithOutputs(
            String taskId, String actionKey, Map<String, String> inputs, Map<String, String> outputs)
            throws IOException {
        return storeWithOutputs(taskId, actionKey, inputs, outputs, Map.of());
    }

    /** As above, plus the per-source {@code units} grouping (incremental builds). */
    public ActionRecord storeWithOutputs(
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Map<String, List<String>> units)
            throws IOException {
        return storeWithOutputs(taskId, actionKey, inputs, outputs, units, Set.of());
    }

    /** As above, recording which outputs were executable so a restore can put the bit back. */
    public ActionRecord storeWithOutputs(
            @Nullable String taskId,
            @Nullable String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Map<String, List<String>> units,
            Set<String> executables)
            throws IOException {
        Files.createDirectories(keysDir());
        Files.createDirectories(tasksDir());
        meter(outputs, true); // every store path funnels here — one place to count cache-in bytes
        ActionRecord record = new ActionRecord(taskId, actionKey, inputs, outputs, units, executables);
        // Atomic temp+move: concurrent store/lookup under cacheGate read mode must never see a
        // truncated keys/ or tasks/ file. Order preserved: key before task pointer.
        AtomicWrites.replace(keysDir().resolve(actionKey), render(record));
        // Generation trim for Class-C (native / OCI / fat assembly) before flipping the pointer.
        String previous = null;
        Path pointer = tasksDir().resolve(taskId);
        if (Files.isRegularFile(pointer)) {
            try {
                previous = Files.readString(pointer).trim();
            } catch (IOException ignored) {
                previous = null;
            }
        }
        AtomicWrites.replace(pointer, Objects.requireNonNull(actionKey, "actionKey"));
        trimGenerations(taskId, actionKey, previous);
        return record;
    }

    /**
     * Keep at most {@link HeavyActionPolicy#generations(String)} action keys for Class-C tasks:
     * current pointer + {@code tasks/<taskId>.gens} (newest first). Older key files are deleted so
     * {@link CasSweep} can reclaim their blobs.
     */
    private void trimGenerations(@Nullable String taskId, @Nullable String newKey, @Nullable String previousKey)
            throws IOException {
        int keep = HeavyActionPolicy.generations(taskId);
        if (keep == Integer.MAX_VALUE) return; // not Class-C
        Path gens = HeavyActionPolicy.gensFile(tasksDir(), taskId);
        List<String> history = new ArrayList<>();
        if (previousKey != null && !previousKey.isBlank() && !previousKey.equals(newKey)) {
            history.add(previousKey);
        }
        if (Files.isRegularFile(gens)) {
            for (String line : Files.readAllLines(gens)) {
                String k = line.trim();
                if (k.isEmpty() || k.equals(newKey) || history.contains(k)) continue;
                history.add(k);
            }
        }
        // keep total generations including current (newKey): retain keep-1 predecessors
        int retain = Math.max(0, keep - 1);
        List<String> drop = new ArrayList<>();
        if (history.size() > retain) {
            drop.addAll(history.subList(retain, history.size()));
            history = new ArrayList<>(history.subList(0, retain));
        }
        for (String k : drop) {
            Files.deleteIfExists(keysDir().resolve(k));
        }
        if (history.isEmpty()) {
            Files.deleteIfExists(gens);
        } else {
            Files.createDirectories(gens.getParent());
            AtomicWrites.replace(gens, String.join("\n", history) + "\n");
        }
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
        return storeWithOutputs(taskId, actionKey, inputs, outputs, Map.of(), executables);
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

    public record ActionRecord(
            @Nullable String taskId,
            @Nullable String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Map<String, List<String>> units,
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
            // units: source-abs-path → output relPaths it produced. Populated by
            // an incremental compiler; empty for full rebuilds.
            Map<String, List<String>> u = new LinkedHashMap<>();
            if (units != null) units.forEach((k, v) -> u.put(k, List.copyOf(v)));
            units = Map.copyOf(u);
        }

        public ActionRecord(
                @Nullable String taskId,
                @Nullable String actionKey,
                Map<String, String> inputs,
                Map<String, String> outputs,
                Map<String, List<String>> units) {
            this(taskId, actionKey, inputs, outputs, units, Set.of());
        }
    }

    private static String render(ActionRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(record.taskId()).append('\n');
        sb.append("KEY ").append(record.actionKey()).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(record.inputs()).entrySet()) {
            sb.append("INPUT ")
                    .append(e.getValue())
                    .append(' ')
                    .append(e.getKey())
                    .append('\n');
        }
        for (Map.Entry<String, String> e : new TreeMap<>(record.outputs()).entrySet()) {
            sb.append("OUTPUT ")
                    .append(e.getValue())
                    .append(' ')
                    .append(e.getKey())
                    .append('\n');
        }
        // EXEC <relPath> — the output was executable when stored.
        for (String rel : new TreeSet<>(record.executables())) {
            sb.append("EXEC ").append(rel).append('\n');
        }
        // UNIT <relPath> <sourceAbsPath> — relPath is space-free (Java class
        // path), source is the rest of the line so it may contain spaces.
        for (Map.Entry<String, List<String>> e : new TreeMap<>(record.units()).entrySet()) {
            List<String> rels = new ArrayList<>(e.getValue());
            rels.sort(Comparator.naturalOrder());
            for (String rel : rels) {
                sb.append("UNIT ").append(rel).append(' ').append(e.getKey()).append('\n');
            }
        }
        return sb.toString();
    }

    private static ActionRecord parse(String content) {
        String taskId = null;
        String actionKey = null;
        Map<String, String> inputs = new LinkedHashMap<>();
        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, List<String>> units = new LinkedHashMap<>();
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
                inputs.put(body.substring(sp + 1), body.substring(0, sp));
            } else if (line.startsWith("OUTPUT ")) {
                String body = line.substring("OUTPUT ".length());
                int sp = body.indexOf(' ');
                outputs.put(body.substring(sp + 1), body.substring(0, sp));
            } else if (line.startsWith("EXEC ")) {
                executables.add(line.substring("EXEC ".length()).trim());
            } else if (line.startsWith("UNIT ")) {
                // UNIT <relPath> <sourceAbsPath> — omitted when the compiler recorded no per-source units.
                String body = line.substring("UNIT ".length());
                int sp = body.indexOf(' ');
                String rel = body.substring(0, sp);
                String source = body.substring(sp + 1);
                units.computeIfAbsent(source, k -> new ArrayList<>()).add(rel);
            }
        }
        return new ActionRecord(
                Objects.requireNonNull(taskId, "taskId in record"),
                Objects.requireNonNull(actionKey, "actionKey in record"),
                inputs,
                outputs,
                units,
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
