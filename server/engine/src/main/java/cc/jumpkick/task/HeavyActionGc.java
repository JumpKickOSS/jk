// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reclaim Class-C action-cache entries ({@link HeavyActionPolicy}): 3-day unused TTL, then shrink
 * Class-C blob bytes to {@link HeavyActionPolicy#BUDGET_FRACTION} of the cache budget. Deletes
 * action <em>keys</em> (and trims task pointers / generation lists); {@link CasSweep} frees
 * orphaned blobs afterwards.
 */
public final class HeavyActionGc {

    private static final Pattern TASK = Pattern.compile("^TASK (\\S+)", Pattern.MULTILINE);
    private static final Pattern OUTPUT_SHA = Pattern.compile("^OUTPUT ([0-9a-fA-F]{64}) ", Pattern.MULTILINE);

    private HeavyActionGc() {}

    public record Report(int deletedKeys, long classCBytesBefore, long classCBytesAfter) {}

    /**
     * Delete <em>every</em> Class-C action key (native / OCI / fat assembly). Used by {@code jk
     * cache clean} so the first space-reclaim knob drops heavy ship outputs while keeping modular
     * compile/test cache. Orphaned blobs are freed by a following {@link CasSweep}.
     */
    public static Report purgeAll(Path cacheRoot, Cas cacheCas, boolean dryRun) throws IOException {
        Path keysDir = cacheRoot.resolve("actions").resolve("keys");
        Path tasksDir = cacheRoot.resolve("actions").resolve("tasks");
        if (!Files.isDirectory(keysDir)) return new Report(0, 0L, 0L);
        List<Entry> heavy = collectClassC(cacheCas, keysDir);
        long before = heavy.stream().mapToLong(Entry::bytes).sum();
        int deleted = 0;
        for (Entry e : heavy) {
            if (!dryRun) deleteKey(keysDir, tasksDir, e.actionKey, e.taskId);
            deleted++;
        }
        return new Report(deleted, before, 0L);
    }

    /**
     * @param cacheRoot session cache root ({@code actions/} lives under it)
     * @param cacheCas action-payload CAS
     * @param maxCacheBytes overall cache budget (0 = skip share cap, still run TTL)
     * @param ttl Class-C unused TTL
     */
    public static Report sweep(Path cacheRoot, Cas cacheCas, long maxCacheBytes, Duration ttl, boolean dryRun)
            throws IOException {
        Path keysDir = cacheRoot.resolve("actions").resolve("keys");
        Path tasksDir = cacheRoot.resolve("actions").resolve("tasks");
        if (!Files.isDirectory(keysDir)) return new Report(0, 0L, 0L);

        long cutoff = System.currentTimeMillis() - ttl.toMillis();
        List<Entry> heavy = collectClassC(cacheCas, keysDir);

        long before = heavy.stream().mapToLong(Entry::bytes).sum();
        int deleted = 0;
        Set<String> deletedKeys = new HashSet<>();

        // Phase 1: TTL
        for (Entry e : heavy) {
            if (e.atime >= cutoff) continue;
            if (!dryRun) deleteKey(keysDir, tasksDir, e.actionKey, e.taskId);
            deletedKeys.add(e.actionKey);
            deleted++;
        }

        // Phase 2: share of budget (among survivors)
        long budget = HeavyActionPolicy.classCBudgetBytes(maxCacheBytes);
        if (budget > 0) {
            List<Entry> survivors = new ArrayList<>();
            for (Entry e : heavy) {
                if (deletedKeys.contains(e.actionKey)) continue;
                survivors.add(e);
            }
            survivors.sort(Comparator.comparingLong(Entry::atime)); // oldest first
            long liveBytes = survivors.stream().mapToLong(Entry::bytes).sum();
            for (Entry e : survivors) {
                if (liveBytes <= budget) break;
                if (!dryRun) deleteKey(keysDir, tasksDir, e.actionKey, e.taskId);
                deletedKeys.add(e.actionKey);
                deleted++;
                liveBytes -= e.bytes;
            }
        }

        long after = 0L;
        for (Entry e : heavy) {
            if (!deletedKeys.contains(e.actionKey)) after += e.bytes;
        }
        return new Report(deleted, before, after);
    }

    private record Entry(Path keyFile, String actionKey, String taskId, List<String> shas, long atime, long bytes) {}

    /**
     * Digests still referenced by live Class-C action keys. Used by size-cap eviction so heavy ship
     * outputs are preferred victims over modular compile/test blobs (JK-1721).
     */
    public static Set<String> liveClassCShas(Path cacheRoot, Cas cacheCas) throws IOException {
        Path keysDir = cacheRoot.resolve("actions").resolve("keys");
        if (!Files.isDirectory(keysDir)) return Set.of();
        Set<String> shas = new HashSet<>();
        for (Entry e : collectClassC(cacheCas, keysDir)) {
            shas.addAll(e.shas);
        }
        return shas;
    }

    private static List<Entry> collectClassC(Cas cacheCas, Path keysDir) throws IOException {
        Map<String, Long> atimes = AccessLedger.atDefaultPath().latestByHash();
        List<Entry> heavy = new ArrayList<>();
        try (Stream<Path> stream = Files.list(keysDir)) {
            for (Path keyFile : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String body = Files.readString(keyFile, StandardCharsets.UTF_8);
                Matcher tm = TASK.matcher(body);
                if (!tm.find()) continue;
                String taskId = tm.group(1);
                if (!HeavyActionPolicy.isClassC(taskId)) continue;
                List<String> shas = new ArrayList<>();
                Matcher om = OUTPUT_SHA.matcher(body);
                while (om.find()) shas.add(om.group(1).toLowerCase());
                long bytes = 0L;
                long atime = Files.getLastModifiedTime(keyFile).toMillis();
                for (String sha : shas) {
                    Path blob = cacheCas.pathFor(sha);
                    if (Files.isRegularFile(blob)) {
                        bytes += Files.size(blob);
                        Long touch = atimes.get(sha);
                        if (touch != null && touch > atime) atime = touch;
                    }
                }
                heavy.add(new Entry(keyFile, keyFile.getFileName().toString(), taskId, shas, atime, bytes));
            }
        }
        return heavy;
    }

    private static void deleteKey(Path keysDir, Path tasksDir, String actionKey, String taskId) throws IOException {
        Files.deleteIfExists(keysDir.resolve(actionKey));
        // Drop from current pointer / generation list if present.
        Path pointer = tasksDir.resolve(taskId);
        if (Files.isRegularFile(pointer)) {
            String cur = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            if (actionKey.equals(cur)) Files.deleteIfExists(pointer);
        }
        Path gens = gensFile(tasksDir, taskId);
        if (Files.isRegularFile(gens)) {
            List<String> kept = new ArrayList<>();
            for (String line : Files.readAllLines(gens, StandardCharsets.UTF_8)) {
                String k = line.trim();
                if (!k.isEmpty() && !k.equals(actionKey)) kept.add(k);
            }
            if (kept.isEmpty()) Files.deleteIfExists(gens);
            else Files.write(gens, kept, StandardCharsets.UTF_8);
        }
    }

    static Path gensFile(Path tasksDir, String taskId) {
        return tasksDir.resolve(taskId + ".gens");
    }
}
