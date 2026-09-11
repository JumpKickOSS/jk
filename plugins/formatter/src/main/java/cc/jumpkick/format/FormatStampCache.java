// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What one formatter configuration already knows about a file's bytes, in two index files under
 * {@code <cache>/format/stamps/}: {@code <configKey>.keys} for bytes that are settled, and
 * {@code <configKey>.timeouts} for bytes that defeated the formatter. Both are held in memory for
 * the run and written once.
 *
 * <p>Both are verdicts about the same thing — this content, under this configuration — so they are
 * mutually exclusive and the newer one replaces the older. A {@code .keys} hit means the formatter
 * can skip the file; a {@code .timeouts} hit means it should not spend the per-file limit reaching
 * the same error again.
 *
 * <p>Fail-open throughout: a lost or corrupt index costs one extra format pass and never an error.
 * Recording is a map write; each index is one read at load and one write at {@link #save()}.
 */
final class FormatStampCache {

    /**
     * Keys one index keeps. The population is content-keyed, so it grows with every distinct version
     * of every file ever formatted rather than with the file count — it needs a cap in a way
     * {@code FormatFreshnessIndex} (one entry per path) does not. At ~65 B per line this is ~4 MB.
     */
    private static final int MAX_ENTRIES = 65_536;

    /**
     * Timeouts one index keeps. A file that defeats the formatter is rare by construction — a
     * population anywhere near this cap means something other than one bad expression is wrong — so
     * this is a bound on pathology, not on ordinary growth.
     */
    private static final int MAX_TIMEOUTS = 1_024;

    /** Suffixes for the two files per configuration. */
    private static final String INDEX_SUFFIX = ".keys";

    private static final String TIMEOUT_SUFFIX = ".timeouts";

    private final Path index;
    private final Path timeoutIndex;
    private final String configKey;

    /** key → use tick. A map rather than a set because eviction ranks by recency. */
    private final ConcurrentMap<String, Long> keys = new ConcurrentHashMap<>();

    /** key → the per-file limit these bytes blew through, and when the fact was last consulted. */
    private final ConcurrentMap<String, Timeout> timeouts = new ConcurrentHashMap<>();

    /** One remembered timeout: the limit it happened under, and its recency for eviction. */
    private record Timeout(long limitMs, long tick) {}

    private final AtomicLong tick = new AtomicLong();
    private volatile boolean dirty;

    /**
     * {@code configKey} is the host's {@code FormatKey} digest, verbatim. The worker never derives its
     * own. Loading is best-effort: an unreadable index starts empty and refills.
     */
    FormatStampCache(Path root, String configKey) {
        this.configKey = configKey;
        this.index = root.resolve(configKey + INDEX_SUFFIX);
        this.timeoutIndex = root.resolve(configKey + TIMEOUT_SUFFIX);
        load();
        loadTimeouts();
        sweepResidue(root);
    }

    /**
     * The stamp key for a file whose raw bytes are {@code fileBytes}: SHA-256 over the run's config
     * digest and the content. Null for absent bytes (fail-open cache miss).
     */
    String keyFor(byte[] fileBytes) {
        if (fileBytes == null) return null;
        MessageDigest md = Hashing.newSha256();
        md.update((configKey + "\n").getBytes(StandardCharsets.UTF_8));
        md.update(fileBytes);
        return Hashing.hex(md.digest());
    }

    /**
     * Whether {@code key} is settled. A map read — no filesystem call at all. A hit is touched, so
     * {@link #save()} ranks what this run actually used above what it merely loaded.
     */
    boolean contains(String key) {
        if (key == null) return false;
        if (keys.replace(key, tick.incrementAndGet()) == null) return false;
        dirty = true;
        return true;
    }

    /** Remember {@code key} as settled. A map write; nothing reaches disk until {@link #save()}. */
    void record(String key) {
        if (key == null) return;
        keys.put(key, tick.incrementAndGet());
        // These bytes formatted, so whatever they did on an earlier run under a tighter limit is no
        // longer the verdict on them.
        timeouts.remove(key);
        dirty = true;
    }

    /**
     * The per-file limit, in milliseconds, that {@code key}'s bytes already blew through under this
     * configuration — or {@code 0} when they have not. A hit is touched, so {@link #save()} ranks what
     * this run actually consulted above what it merely loaded.
     */
    long timedOutAt(String key) {
        if (key == null) return 0;
        Timeout t = timeouts.computeIfPresent(key, (k, v) -> new Timeout(v.limitMs(), tick.incrementAndGet()));
        if (t == null) return 0;
        dirty = true;
        return t.limitMs();
    }

    /**
     * Remember that {@code key}'s bytes did not finish inside {@code limitMs}. Replaces any settled
     * stamp for the same bytes: the run just proved otherwise.
     */
    void recordTimeout(String key, long limitMs) {
        if (key == null || limitMs <= 0) return;
        timeouts.put(key, new Timeout(limitMs, tick.incrementAndGet()));
        keys.remove(key);
        dirty = true;
    }

    /**
     * Write both indexes once, most-recently-used last, capped at {@link #MAX_ENTRIES} and
     * {@link #MAX_TIMEOUTS}.
     *
     * <p>Called at the end of a run. Not atomic-replaced: this is a pure cache whose loss costs one
     * format pass, and {@code AtomicWrites} is a 372.7&nbsp;µs operation on NTFS buying durability no
     * reader of this file needs. A torn index simply fails to parse and starts empty.
     */
    void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(index.getParent());
            List<Map.Entry<String, Long>> ordered = new ArrayList<>(keys.entrySet());
            ordered.sort(Comparator.comparingLong(Map.Entry::getValue));
            int drop = Math.max(0, ordered.size() - MAX_ENTRIES);
            StringBuilder sb = new StringBuilder(Math.min(ordered.size(), MAX_ENTRIES) * 66);
            for (int i = drop; i < ordered.size(); i++) {
                sb.append(ordered.get(i).getKey()).append('\n');
            }
            Files.writeString(index, sb.toString());
            saveTimeouts();
            dirty = false;
        } catch (IOException | RuntimeException ignored) {
            // A cache, never a requirement.
        }
    }

    /**
     * Write the timeout index, most-recently-consulted last, capped at {@link #MAX_TIMEOUTS}. One
     * line per entry: the key, a space, and the limit it blew through — the limit is what lets a
     * later run with a more generous one try again instead of inheriting the verdict.
     *
     * <p>Deleted rather than left behind when the run has no timeouts, so a fixed file stops costing
     * every later run a read of a stale list.
     */
    private void saveTimeouts() throws IOException {
        if (timeouts.isEmpty()) {
            Files.deleteIfExists(timeoutIndex);
            return;
        }
        List<Map.Entry<String, Timeout>> ordered = new ArrayList<>(timeouts.entrySet());
        ordered.sort(Comparator.comparingLong(e -> e.getValue().tick()));
        int drop = Math.max(0, ordered.size() - MAX_TIMEOUTS);
        StringBuilder sb = new StringBuilder();
        for (int i = drop; i < ordered.size(); i++) {
            sb.append(ordered.get(i).getKey())
                    .append(' ')
                    .append(ordered.get(i).getValue().limitMs())
                    .append('\n');
        }
        Files.writeString(timeoutIndex, sb.toString());
    }

    /** Load the timeout index. A line this cannot parse is a line that never happened. */
    private void loadTimeouts() {
        try {
            for (String line : Files.readAllLines(timeoutIndex, StandardCharsets.UTF_8)) {
                int space = line.indexOf(' ');
                if (space <= 0) continue;
                try {
                    long limitMs = Long.parseLong(line.substring(space + 1).strip());
                    if (limitMs > 0) {
                        timeouts.put(line.substring(0, space), new Timeout(limitMs, tick.incrementAndGet()));
                    }
                } catch (NumberFormatException ignored) {
                    // A half-written line is not a verdict; the file gets formatted for real.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // No timeouts recorded yet, or an unreadable list: nothing is remembered.
        }
    }

    /** Load the index, oldest line first so file order becomes use order. */
    private void load() {
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                String key = line.strip();
                if (!key.isEmpty()) keys.put(key, tick.incrementAndGet());
            }
        } catch (IOException | RuntimeException ignored) {
            // No index yet, or an unreadable one: start empty and refill.
        }
    }

    /**
     * Remove anything in the tier that is not an index file.
     *
     * <p>Residue includes a sharded {@code <aa>/<bb>/<60-hex>} tree nothing reads; retention ranks
     * the tier by mtime, so leftover entries would sit for a week and stay invisible to every byte
     * report. One {@code list} of a directory that normally holds a handful of index files.
     *
     * <p>Other configurations' indexes are kept — several coexist here, one per configuration a
     * workspace formats with, so two workers sweeping concurrently cannot delete each other's.
     */
    private static void sweepResidue(Path root) {
        try (var children = Files.list(root)) {
            for (Path p : (Iterable<Path>) children::iterator) {
                String name = p.getFileName().toString();
                if (Files.isRegularFile(p) && (name.endsWith(INDEX_SUFFIX) || name.endsWith(TIMEOUT_SUFFIX))) {
                    continue;
                }
                PathUtil.deleteRecursively(p);
            }
        } catch (IOException | RuntimeException ignored) {
            // Housekeeping, never load-bearing.
        }
    }

    /** Test seam: entries currently held. */
    int size() {
        return keys.size();
    }

    /** Test seam: remembered timeouts currently held. */
    int timeoutCount() {
        return timeouts.size();
    }
}
