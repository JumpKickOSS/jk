// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.Hashing;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/**
 * Persistent action cache: {@code action_key → CAS outputs} plus a project-qualified {@code task
 * → action_key} pointer. Layout: {@code keys/<actionKey>}, {@code tasks/<taskId>}. Store/restore
 * always copy (never hard-link) so compile trees cannot poison CAS blobs.
 *
 * <p>Optional {@code storeCas}: Class-C blobs promoted by {@link cc.jumpkick.cache.ActionPromote} /
 * {@code jk release} may live only in the artifact store — restore falls back there on cache miss.
 * Class-C tasks also keep a short generation list ({@code tasks/<taskId>.gens}).
 */
public final class ActionCache {

    private final Cas cas;
    private final Cas storeCas; // nullable — dual-CAS lookup for promoted Class-C blobs
    private final Path root;

    public ActionCache(Cas cas, Path root) {
        this(cas, root, null);
    }

    /**
     * @param storeCas optional long-lived store CAS for Class-C blob fallback (may be null)
     */
    public ActionCache(Cas cas, Path root, Cas storeCas) {
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

    public Optional<ActionRecord> lookup(String actionKey) throws IOException {
        Path file = keysDir().resolve(actionKey);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(parse(Files.readString(file)));
    }

    public Optional<ActionRecord> lastFor(String taskId) throws IOException {
        Path pointer = tasksDir().resolve(taskId);
        if (!Files.exists(pointer)) return Optional.empty();
        String actionKey = Files.readString(pointer).trim();
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
            try (Stream<Path> stream = Files.walk(outputDir)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(file)) continue;
                    // FreshnessStamp's sentinels (.jstamp/.kstamp) live inside
                    // outputDir but aren't action outputs — exclude them so we
                    // don't accidentally cache a stamp from a previous run.
                    if (FreshnessStamp.isStampFile(file.getFileName().toString())) continue;
                    // `.jk-*` scratch (a plugin's private bootstrap repo/staging — the
                    // plugin-sdk copyTree convention) is never an action output.
                    if (hasJkScratchSegment(outputDir.relativize(file))) continue;
                    // Hash once, then COPY into the CAS (never link — see Cas.putFile).
                    String hex = Hashing.sha256Hex(file);
                    cas.putFile(file, hex);
                    String relPath = outputDir.relativize(file).toString().replace(File.separatorChar, '/');
                    outputs.put(relPath, hex);
                    if (Files.isExecutable(file)) executables.add(relPath);
                }
            }
        }
        // Refuse empty success records: a non-empty source set that produced zero classes
        // must not become a cache hit that restores an empty tree on the next build.
        if (outputs.isEmpty() && hasSourceInputs(inputs)) {
            return new ActionRecord(taskId, actionKey, inputs, Map.of(), Map.of());
        }
        return storeWithOutputs(taskId, actionKey, inputs, outputs, Map.of(), executables);
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
            String taskId,
            String actionKey,
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
        AtomicWrites.replace(pointer, actionKey);
        trimGenerations(taskId, actionKey, previous);
        return record;
    }

    /**
     * Keep at most {@link HeavyActionPolicy#generations(String)} action keys for Class-C tasks:
     * current pointer + {@code tasks/<taskId>.gens} (newest first). Older key files are deleted so
     * {@link CasSweep} can reclaim their blobs.
     */
    private void trimGenerations(String taskId, String newKey, String previousKey) throws IOException {
        int keep = HeavyActionPolicy.generations(taskId);
        if (keep == Integer.MAX_VALUE) return; // not Class-C
        Path gens = HeavyActionGc.gensFile(tasksDir(), taskId);
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
        for (String sha : record.outputs().values()) {
            if (!hasBlob(sha)) return false;
        }
        // Build-host compile freshness stamps (.jstamp/.kstamp) live inside the
        // classes tree but are NOT part of the cached compiled output — they're
        // written by a *later* step (write-stamp) of the previous build. Preserve
        // them across the clear+restore so a compile cache-hit/incremental restore
        // doesn't wipe the stamp a later step relies on. (The test result is a CAS
        // marker now, not a file here — see TestStamp.)
        Map<String, byte[]> stamps = new LinkedHashMap<>();
        for (String f :
                new String[] {FreshnessStamp.JAVA_STAMP, FreshnessStamp.KOTLIN_STAMP, FreshnessStamp.GROOVY_STAMP}) {
            Path sp = outputDir.resolve(f);
            if (Files.isRegularFile(sp)) stamps.put(f, Files.readAllBytes(sp));
        }
        if (Files.exists(outputDir)) {
            deleteRecursively(outputDir);
        }
        Files.createDirectories(outputDir);
        for (Map.Entry<String, byte[]> e : stamps.entrySet()) {
            Files.write(outputDir.resolve(e.getKey()), e.getValue());
        }
        meter(record.outputs(), false); // cache hit: these bytes come back out of the cache
        AccessLedger ledger = AccessLedger.atDefaultPath();
        for (Map.Entry<String, String> entry : record.outputs().entrySet()) {
            Path target = outputDir.resolve(entry.getKey());
            // COPY, never link: compilers rewrite restored class files IN PLACE on the next
            // build, and a hard link would let that rewrite mutate the CAS blob (see
            // Cas.putFile). Costs O(bytes) instead of O(entries) — correctness wins.
            Files.createDirectories(target.getParent());
            // Digest while copying: the memo seed below asserts these exact bytes, so a
            // truncated/corrupt blob must surface as a miss here — not as green tests over
            // wrong classes downstream.
            if (!copyVerified(resolveBlob(entry.getValue()), target, entry.getValue())) {
                dropCorruptBlob(entry.getValue());
                deleteRecursively(outputDir);
                Files.createDirectories(outputDir);
                return false;
            }
            // CAS blobs carry no mode; the record does. Same reason as restoreArtifacts.
            if (record.executables().contains(entry.getKey())) {
                target.toFile().setExecutable(true, false);
            }
            // Seed content memo so TestStamp / package keys do not re-hash the whole tree.
            FileHashMemo.rememberContent(target, entry.getValue());
            // Best-effort access journal — feeds the LRU evictor when the
            // user configures a cache size budget.
            ledger.touch(entry.getValue());
        }
        return true;
    }

    /**
     * Copy {@code blob} to {@code target} computing SHA-256 on the way; true when the bytes match
     * {@code expectedSha}. A mismatching target is deleted before returning false.
     */
    private static boolean copyVerified(Path blob, Path target, String expectedSha) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (var in = Files.newInputStream(blob);
                var out = new DigestOutputStream(
                        Files.newOutputStream(
                                target,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE),
                        md)) {
            in.transferTo(out);
        }
        if (expectedSha.equalsIgnoreCase(HexFormat.of().formatHex(md.digest()))) {
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
        AccessLedger ledger = AccessLedger.atDefaultPath();
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
            ledger.touch(e.getValue());
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
        return sha.equals(Hashing.sha256Hex(target));
    }

    /**
     * CAS-store already-produced {@code artifacts} (copying each into the CAS) and write an
     * {@link ActionRecord} keyed by {@code actionKey}, with each artifact's {@code baseDir}-relative
     * path as its output key. The companion of {@link #restoreArtifacts} for single/few-file
     * packaging.
     */
    public ActionRecord storeArtifacts(
            String taskId, String actionKey, Map<String, String> inputs, Path baseDir, List<Path> artifacts)
            throws IOException {
        Map<String, String> outputs = new TreeMap<>();
        Set<String> executables = new TreeSet<>();
        for (Path a : artifacts) {
            if (!Files.isRegularFile(a)) continue;
            String hex = Hashing.sha256Hex(a);
            cas.putFile(a, hex); // never link a mutable target/ artifact into the CAS

            String rel = baseDir.relativize(a).toString().replace(File.separatorChar, '/');
            outputs.put(rel, hex);
            if (Files.isExecutable(a)) executables.add(rel);
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
            String taskId,
            String actionKey,
            Map<String, String> inputs,
            Map<String, String> outputs,
            Map<String, List<String>> units,
            /**
             * Output rel-paths that were executable when stored. CAS blobs carry no mode, so
             * without this a restored binary comes back 0644 and the artifact is unrunnable from
             * the second build onwards. Empty for records written before this was recorded, which
             * restore exactly as they used to.
             */
            Set<String> executables) {

        public ActionRecord {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(actionKey, "actionKey");
            inputs = Map.copyOf(inputs);
            outputs = Map.copyOf(outputs);
            executables = executables == null ? Set.of() : Set.copyOf(executables);
            // units: source-abs-path → output relPaths it produced. Populated by
            // an incremental compiler; empty for full rebuilds / legacy records.
            Map<String, List<String>> u = new LinkedHashMap<>();
            if (units != null) units.forEach((k, v) -> u.put(k, List.copyOf(v)));
            units = Map.copyOf(u);
        }

        public ActionRecord(
                String taskId,
                String actionKey,
                Map<String, String> inputs,
                Map<String, String> outputs,
                Map<String, List<String>> units) {
            this(taskId, actionKey, inputs, outputs, units, Set.of());
        }

        /** Back-compat: a record with no per-source unit grouping. */
        public ActionRecord(String taskId, String actionKey, Map<String, String> inputs, Map<String, String> outputs) {
            this(taskId, actionKey, inputs, outputs, Map.of(), Set.of());
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
                // UNIT <relPath> <sourceAbsPath> — absent in legacy records.
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
        return root.resolve("keys");
    }

    private Path tasksDir() {
        return root.resolve("tasks");
    }

    private static void deleteRecursively(Path target) throws IOException {
        cc.jumpkick.util.PathUtil.deleteRecursivelyOrThrow(target);
    }
}
