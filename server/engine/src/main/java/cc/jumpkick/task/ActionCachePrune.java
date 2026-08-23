// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Size-bound the action cache by deleting whole entries — an action key plus the blobs no surviving
 * key still references — oldest key mtime first.
 *
 * <p>The order is the key file's mtime, never a blob's. A key is content-addressed over its inputs,
 * so its mtime is the last time that exact action ran; once the inputs change a new key is minted
 * and the old one can never be useful again. A blob is shared by several keys and the CAS is
 * write-once ({@link Cas#putFile} skips an existing path), so a blob's mtime is its <em>first</em>
 * store time — an old blob under a young key means "these bytes were first seen long ago and are
 * still current", and ranking on it would evict live work.
 *
 * <p>That makes this FIFO by last <em>store</em>, not LRU: a hit neither rewrites the key nor
 * touches the blob, so an entry that has hit daily for six months still sorts by the day it was
 * minted, and a stable module can be evicted ahead of a churning one's already-dead keys. The
 * alternative — stamping the key on every hit — was rejected because it puts a write on the hot
 * read path of a parallel build, and because a wrong eviction is self-repairing: the next build
 * re-runs the action and re-stores the key with a fresh mtime, so each mistake costs one recompute
 * and cannot repeat for the same entry.
 *
 * <p>Runs after {@link CasSweep} in the same pass so reclaimed garbage covers the shortfall before
 * anything live is evicted.
 */
public final class ActionCachePrune {

    private static final Pattern TASK = Pattern.compile("^TASK (\\S+)", Pattern.MULTILINE);
    private static final Pattern OUTPUT_SHA = Pattern.compile("^OUTPUT ([0-9a-fA-F]{64}) ", Pattern.MULTILINE);

    private ActionCachePrune() {}

    /**
     * @param deletedKeys action keys removed, each with its task pointer and generation entry
     * @param deletedBlobs cache-CAS blobs unlinked with their last referencing key
     * @param freedBytes key-record plus blob bytes removed (dry run: that would be). Task pointers
     *     and generation lists go with the key but are not counted — roughly 64 bytes an entry, so
     *     the pass errs towards evicting one entry too many rather than one too few
     * @param finalBytes action-cache bytes after the pass; may exceed {@code budgetBytes} when every
     *     remaining entry is inside the write grace window, and is {@code 0} when {@code
     *     budgetBytes <= 0} disabled the pass before anything was measured
     */
    public record Report(int deletedKeys, int deletedBlobs, long freedBytes, long finalBytes) {}

    /**
     * Delete whole action-cache entries, oldest key mtime first, until {@code actions/} plus the
     * cache CAS fit {@code budgetBytes}. {@code budgetBytes <= 0} is a no-op that measures nothing
     * and reports zeroes. {@code alreadyFreedShas} are blobs a same-pass {@link CasSweep} claimed —
     * excluded from both the measured total and the candidate blobs so a dry run reports the same
     * numbers as a real one.
     *
     * <p>Entries and blobs touched within {@link Sweep#MIN_AGE_FOR_SWEEP} are never taken: the
     * caller's locks keep this engine's builds out of the way, but a second engine sharing the cache
     * root holds none of them and may be mid-{@link ActionCache#restore}. The budget is therefore
     * soft — under continuous write pressure {@code finalBytes} above {@code budgetBytes} is a
     * legitimate outcome.
     */
    public static Report toBudget(
            Path cacheRoot, Cas cacheCas, long budgetBytes, Set<String> alreadyFreedShas, boolean dryRun)
            throws IOException {
        if (budgetBytes <= 0) return new Report(0, 0, 0L, 0L);

        long now = System.currentTimeMillis();
        long graceMillis = Sweep.MIN_AGE_FOR_SWEEP.toMillis();

        Map<String, Long> blobSize = new HashMap<>();
        Map<String, Long> blobMtime = new HashMap<>();
        long used = scanCas(cacheCas, alreadyFreedShas, blobSize, blobMtime);

        Path actionsDir = cacheRoot.resolve("actions");
        Path keysDir = actionsDir.resolve("keys");
        Path tasksDir = actionsDir.resolve("tasks");
        List<Entry> entries = new ArrayList<>();
        Map<String, Integer> refs = new HashMap<>();
        used += scanActions(actionsDir, keysDir, entries, refs);

        if (used <= budgetBytes) return new Report(0, 0, 0L, used);

        entries.sort(Comparator.comparingLong(Entry::mtime));

        int deletedKeys = 0;
        int deletedBlobs = 0;
        long freed = 0L;
        for (Entry entry : entries) {
            if (used <= budgetBytes) break;
            if (now - entry.mtime() < graceMillis) continue;

            if (!dryRun) deleteKey(keysDir, tasksDir, entry.actionKey(), entry.taskId());
            deletedKeys++;
            freed += entry.bytes();
            used -= entry.bytes();

            for (String sha : entry.shas()) {
                // Key records are the whole root set for this pool, so a refcount of zero really
                // is unreferenced: the cache CAS holds action outputs only, and CacheRoots' other
                // roots (synced manifests, tool envs, jk-local memos) name store-CAS blobs.
                if (refs.merge(sha, -1, Integer::sum) > 0) continue;
                Long size = blobSize.get(sha);
                if (size == null) continue; // swept this pass, or never in this CAS
                if (now - blobMtime.get(sha) < graceMillis) continue;
                if (!dryRun) Files.deleteIfExists(cacheCas.pathFor(sha));
                deletedBlobs++;
                freed += size;
                used -= size;
            }
        }
        return new Report(deletedKeys, deletedBlobs, freed, used);
    }

    /** One action-cache entry: the key record and the outputs it names. */
    private record Entry(String actionKey, String taskId, long bytes, long mtime, Set<String> shas) {}

    /** Records {@code size}/{@code mtime} per live cache-CAS blob and returns their total bytes. */
    private static long scanCas(
            Cas cacheCas, Set<String> alreadyFreedShas, Map<String, Long> blobSize, Map<String, Long> blobMtime)
            throws IOException {
        Path shaRoot = cacheCas.root().resolve("sha256");
        if (!Files.isDirectory(shaRoot)) return 0L;
        long bytes = 0L;
        try (Stream<Path> stream = Files.walk(shaRoot)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                if (file.getFileName().toString().startsWith(".put-")) continue;
                var hex = cacheCas.hashFromPath(file);
                if (hex.isEmpty() || alreadyFreedShas.contains(hex.get())) continue;
                long size = Files.size(file);
                blobSize.put(hex.get(), size);
                blobMtime.put(hex.get(), Files.getLastModifiedTime(file).toMillis());
                bytes += size;
            }
        }
        return bytes;
    }

    /**
     * Fills {@code entries} with every evictable key record under {@code keysDir} and {@code refs}
     * with how many key files name each output sha. Returns the byte total of the <em>whole</em>
     * action index — the number {@code jk cache usage} reports and the budget is measured against —
     * even though the prune can only shrink it by the key records it deletes. Incremental compiler
     * state ({@code incremental-java/}, {@code incremental-kotlin/}) therefore counts against the
     * budget while only {@code jk clean --force} can reclaim it: measuring anything narrower would
     * make the budget disagree with the reported total.
     */
    private static long scanActions(Path actionsDir, Path keysDir, List<Entry> entries, Map<String, Integer> refs)
            throws IOException {
        if (!Files.isDirectory(actionsDir)) return 0L;
        record KeyFile(Path file, long bytes, long mtime) {}
        List<KeyFile> keyFiles = new ArrayList<>();
        long bytes = 0L;
        try (Stream<Path> stream = Files.walk(actionsDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                long size = Files.size(file);
                bytes += size;
                if (keysDir.equals(file.getParent())) {
                    keyFiles.add(new KeyFile(
                            file, size, Files.getLastModifiedTime(file).toMillis()));
                }
            }
        }
        for (KeyFile keyFile : keyFiles) {
            String body;
            try {
                body = Files.readString(keyFile.file(), StandardCharsets.UTF_8);
            } catch (NoSuchFileException vanished) {
                // A concurrent ActionCache.trimGenerations dropped it between the walk and here.
                // Any other read fault propagates: without the record we cannot tell which blobs it
                // roots, and guessing "none" would let another entry's eviction unlink them.
                continue;
            }
            Set<String> shas = new LinkedHashSet<>();
            Matcher outputs = OUTPUT_SHA.matcher(body);
            while (outputs.find()) shas.add(outputs.group(1).toLowerCase(Locale.ROOT));
            for (String sha : shas) refs.merge(sha, 1, Integer::sum);

            Matcher task = TASK.matcher(body);
            // ActionCache always renders TASK first, so a record without one is foreign content,
            // not a jk entry: there is no pointer or generation list to unlink with it. Its shas
            // stay counted above, which is what CacheRoots.collect does with the same text — the
            // prune must not unlink a blob the same pass's sweep considers rooted.
            if (!task.find()) continue;
            entries.add(new Entry(
                    keyFile.file().getFileName().toString(), task.group(1), keyFile.bytes(), keyFile.mtime(), shas));
        }
        return bytes;
    }

    /** Unlink one entry's on-disk footprint: key record, task pointer, generation-list line. */
    private static void deleteKey(Path keysDir, Path tasksDir, String actionKey, String taskId) throws IOException {
        Files.deleteIfExists(keysDir.resolve(actionKey));
        Path pointer = tasksDir.resolve(taskId);
        if (Files.isRegularFile(pointer)) {
            // Deliberately racy compare-and-delete: POSIX has no atomic "delete if contents match",
            // and losing to a concurrent store only costs a re-run of one task.
            String current = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            if (actionKey.equals(current)) Files.deleteIfExists(pointer);
        }
        Path gens = HeavyActionPolicy.gensFile(tasksDir, taskId);
        if (!Files.isRegularFile(gens)) return;
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(gens, StandardCharsets.UTF_8)) {
            String key = line.trim();
            if (!key.isEmpty() && !key.equals(actionKey)) kept.add(key);
        }
        // Atomic like the writer side (ActionCache.trimGenerations): a second engine sharing this
        // cache root holds neither the cache gate nor .prune.lock, so a torn plain write here would
        // clobber its concurrent update.
        if (kept.isEmpty()) Files.deleteIfExists(gens);
        else AtomicWrites.replace(gens, String.join("\n", kept) + "\n");
    }
}
