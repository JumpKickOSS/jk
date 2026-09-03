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
 * Settled-file stamps for one formatter configuration: <strong>one index file</strong> at
 * {@code <cache>/format/stamps/<configKey>.keys}, held in memory for the run and written once.
 *
 * <p>A hit means the file's bytes are already settled under the config in the key, so the formatter
 * can skip it. Fail-open throughout: a lost or corrupt index costs one extra format pass and never
 * an error. {@code record} is a map write; the index is one read at load and one write at
 * {@link #save()}.
 */
final class FormatStampCache {

    /**
     * Keys one index keeps. The population is content-keyed, so it grows with every distinct version
     * of every file ever formatted rather than with the file count — it needs a cap in a way
     * {@code FormatFreshnessIndex} (one entry per path) does not. At ~65 B per line this is ~4 MB.
     */
    private static final int MAX_ENTRIES = 65_536;

    /** Suffix for the one file per configuration. */
    private static final String INDEX_SUFFIX = ".keys";

    private final Path index;
    private final String configKey;

    /** key → use tick. A map rather than a set because eviction ranks by recency. */
    private final ConcurrentMap<String, Long> keys = new ConcurrentHashMap<>();

    private final AtomicLong tick = new AtomicLong();
    private volatile boolean dirty;

    /**
     * {@code configKey} is the host's {@code FormatKey} digest, verbatim. The worker never derives its
     * own. Loading is best-effort: an unreadable index starts empty and refills.
     */
    FormatStampCache(Path root, String configKey) {
        this.configKey = configKey;
        this.index = root.resolve(configKey + INDEX_SUFFIX);
        load();
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

    /** Whether {@code key} is settled. A map read — no filesystem call at all. */
    boolean contains(String key) {
        if (key == null) return false;
        // Touch on read so save() keeps what this run actually used.
        return keys.replace(key, tick.incrementAndGet()) != null;
    }

    /** Remember {@code key} as settled. A map write; nothing reaches disk until {@link #save()}. */
    void record(String key) {
        if (key == null) return;
        keys.put(key, tick.incrementAndGet());
        dirty = true;
    }

    /**
     * Write the index once, most-recently-used last, capped at {@link #MAX_ENTRIES}.
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
            dirty = false;
        } catch (IOException | RuntimeException ignored) {
            // A cache, never a requirement.
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
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(INDEX_SUFFIX)) continue;
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
}
