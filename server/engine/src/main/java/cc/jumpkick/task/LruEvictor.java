// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Size-cap eviction over the CAS pool. Runs <em>after</em> {@link CasSweep} — by the time we get
 * here, every survivor is reachable from some root; the only reason to delete more is to respect a
 * user-configured budget.
 *
 * <p>Ordering: prefer low recompute-cost-per-byte victims first ({@code preferEvict} — Class-C
 * heavy ship outputs: natives, OCI tarballs, fat jars), then oldest {@link AccessLedger} touch
 * (mtime fallback), then larger size. A recently-touched 80 MiB native must not displace a cold
 * 20 KiB class blob that costs milliseconds to restore (JK-1721).
 *
 * <p>When the budget forces us to delete a still-reachable object, that's counted as {@code
 * reachableEvicted} and surfaced in the report — the user gets a "your budget is below your live
 * set" signal without us silently corrupting the next build (the action / sync layer naturally
 * re-fetches deleted CAS objects).
 *
 * <p><strong>Disk reclaim:</strong> CAS blobs and {@code repos/} views share an inode via hard
 * link. Eviction unlinks the repo entry ({@code removeShasFromAll}) <em>then</em> the CAS path so
 * nlink reaches zero and {@code --max-size} actually frees space.
 */
public final class LruEvictor {

    private LruEvictor() {}

    public record Report(int deleted, long freedBytes, int reachableEvicted, long finalSize) {}

    /**
     * Evict until the CAS is at or below {@code maxBytes}. No-op when already under budget.
     * {@code reachable} is the live set computed by {@link CacheRoots}; only used here to count how
     * many of the evictees were still in it (for the warning summary).
     */
    public static Report evictDownTo(Cas cas, long maxBytes, Set<String> reachable, AccessLedger ledger, boolean dryRun)
            throws IOException {
        return evictDownTo(cas, maxBytes, reachable, ledger, dryRun, Set.of(), Set.of());
    }

    /**
     * As {@link #evictDownTo(Cas, long, Set, AccessLedger, boolean)}, excluding {@code excluded}
     * shas from both the size total and the candidate list — blobs a same-pass {@link CasSweep}
     * already claimed. In a real run the files are gone before the evictor walks (harmless no-op);
     * in a dry run this keeps FILES/BYTES from counting the same blob twice (JK-1526).
     */
    public static Report evictDownTo(
            Cas cas, long maxBytes, Set<String> reachable, AccessLedger ledger, boolean dryRun, Set<String> excluded)
            throws IOException {
        return evictDownTo(cas, maxBytes, reachable, ledger, dryRun, excluded, Set.of());
    }

    /**
     * As {@link #evictDownTo(Cas, long, Set, AccessLedger, boolean, Set)}, but prefer deleting
     * {@code preferEvict} digests first (Class-C / low recompute-cost-per-byte). Within each
     * preference band, oldest atime first, then larger size.
     */
    public static Report evictDownTo(
            Cas cas,
            long maxBytes,
            Set<String> reachable,
            AccessLedger ledger,
            boolean dryRun,
            Set<String> excluded,
            Set<String> preferEvict)
            throws IOException {
        Path shaRoot = cas.root().resolve("sha256");
        if (!Files.isDirectory(shaRoot)) {
            return new Report(0, 0L, 0, 0L);
        }

        Map<String, Long> atimes = ledger.latestByHash();
        Set<String> prefer = preferEvict != null ? preferEvict : Set.of();

        // Build the candidate list once. Atime falls back to mtime for
        // anything not in the ledger (most things will be, eventually).
        record Entry(Path file, String hex, long size, long atime, boolean reachable, boolean preferred) {}
        List<Entry> entries = new ArrayList<>();
        long totalSize = 0;
        try (Stream<Path> stream = Files.walk(shaRoot)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                if (name.startsWith(".put-")) continue;
                var hexOpt = cas.hashFromPath(file);
                if (hexOpt.isEmpty()) continue;
                String hex = hexOpt.get();
                if (excluded.contains(hex)) continue; // same-pass sweep victim (dry-run parity)
                long size = Files.size(file);
                long atime =
                        atimes.getOrDefault(hex, Files.getLastModifiedTime(file).toMillis());
                entries.add(new Entry(file, hex, size, atime, reachable.contains(hex), prefer.contains(hex)));
                totalSize += size;
            }
        }

        if (totalSize <= maxBytes) {
            return new Report(0, 0L, 0, totalSize);
        }

        // Prefer low recompute-cost-per-byte first; then oldest; then larger for same age.
        entries.sort(Comparator.<Entry>comparingInt(e -> e.preferred() ? 0 : 1)
                .thenComparingLong(Entry::atime)
                .thenComparing(Comparator.comparingLong(Entry::size).reversed()));

        int deleted = 0;
        long freed = 0;
        int reachableEvicted = 0;
        long remaining = totalSize;
        Set<String> deletedShas = new HashSet<>();
        List<Path> casPaths = new ArrayList<>();
        for (Entry e : entries) {
            if (remaining <= maxBytes) break;
            deleted++;
            freed += e.size();
            if (e.reachable()) reachableEvicted++;
            remaining -= e.size();
            deletedShas.add(e.hex());
            casPaths.add(e.file());
        }
        // Repo hard-links first, then CAS paths — both must go or the inode stays allocated.
        cc.jumpkick.repo.RepoArtifactStore.removeShasFromAll(cas.root(), deletedShas, dryRun);
        if (!dryRun) {
            for (Path p : casPaths) {
                Files.deleteIfExists(p);
            }
        }
        return new Report(deleted, freed, reachableEvicted, remaining);
    }

    /**
     * Parse a human-friendly byte size: {@code 500}, {@code 500M}, {@code 1G}, {@code 20GiB}, {@code
     * 2.5GB}. Returns bytes. Throws {@link IllegalArgumentException} on unparseable input.
     */
    public static long parseSize(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("size required");
        }
        String s = spec.trim();
        // Split numeric prefix from unit suffix.
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (i == 0) {
            throw new IllegalArgumentException("size must start with a number: " + spec);
        }
        double n;
        try {
            n = Double.parseDouble(s.substring(0, i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("unparseable size: " + spec, e);
        }
        String unit = s.substring(i).trim().toUpperCase(Locale.ROOT);
        long mult =
                switch (unit) {
                    case "", "B" -> 1L;
                    case "K", "KB", "KIB" -> 1024L;
                    case "M", "MB", "MIB" -> 1024L * 1024;
                    case "G", "GB", "GIB" -> 1024L * 1024 * 1024;
                    case "T", "TB", "TIB" -> 1024L * 1024 * 1024 * 1024;
                    default -> throw new IllegalArgumentException("unknown size unit `" + unit + "` in " + spec);
                };
        return Math.round(n * mult);
    }
}
