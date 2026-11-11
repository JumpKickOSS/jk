// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.ToLongFunction;

/**
 * How often the cache pays for itself, per tier: hits where it saved work (a restore, a replayed
 * green test marker), stores where it did not (every store follows work the cache could not save),
 * and the bytes each moved. The hit rate is {@code hits / (hits + stores)}: counting misses at
 * lookup would also count {@code jk explain} and the forecast, which ask without running anything.
 *
 * <p>Counted in memory and folded into {@code cache-stats.toml} in the state directory at each idle
 * boundary, so the numbers outlive an engine restart. What retention keeps is tuned on these.
 */
public final class CacheStats {

    /** The tiers with their own retention: what a hit or a store counts against. */
    public enum Tier {
        /** Compiled classes, jars and everything else an action stores. */
        ACTION,
        /** Native images, fat jars and any blob of {@link DiskGuard#HEAVY_BYTES} or more. */
        HEAVY,
        /** Records that are the result themselves: a {@code run-tests} green or red marker. */
        MEMO;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final int HITS = 0;
    private static final int STORES = 1;
    private static final int RESTORED = 2;
    private static final int STORED = 3;
    private static final int FIELDS = 4;

    private static final AtomicLongArray LIVE = new AtomicLongArray(Tier.values().length * FIELDS);

    /** The file the totals are folded into, in the state directory. */
    public static final String FILE_NAME = "cache-stats.toml";

    private CacheStats() {}

    /** A hit in {@code tier} that brought {@code bytes} back out of the cache. */
    public static void hit(Tier tier, long bytes) {
        add(tier, HITS, 1);
        add(tier, RESTORED, bytes);
    }

    /** A store in {@code tier} of {@code bytes}: work the cache did not save. */
    public static void store(Tier tier, long bytes) {
        add(tier, STORES, 1);
        add(tier, STORED, bytes);
    }

    private static void add(Tier tier, int field, long n) {
        if (n != 0) LIVE.addAndGet(tier.ordinal() * FIELDS + field, n);
    }

    /**
     * The tier of a record: memo when it names no blob, heavy when its task makes a Class-C output
     * or one of its blobs ({@code size}) is heavy, else action.
     */
    public static Tier classify(String taskId, Map<String, String> outputs, ToLongFunction<String> size) {
        boolean anyBlob = false;
        boolean heavyBlob = false;
        for (String value : outputs.values()) {
            if (!isBlob(value)) continue;
            anyBlob = true;
            if (size.applyAsLong(value) >= DiskGuard.HEAVY_BYTES) heavyBlob = true;
        }
        if (!anyBlob) return Tier.MEMO;
        if (heavyBlob || HeavyActionPolicy.classC(taskId)) return Tier.HEAVY;
        return Tier.ACTION;
    }

    private static boolean isBlob(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    /** One tier's totals. */
    public record Row(Tier tier, long hits, long stores, long restoredBytes, long storedBytes) {
        /** {@code hits / (hits + stores)}, or {@code -1} before the first of either. */
        public double hitRate() {
            long all = hits + stores;
            return all == 0 ? -1 : (double) hits / all;
        }
    }

    /** The totals since {@code sinceMillis}. */
    public record Snapshot(long sinceMillis, List<Row> rows) {}

    /** What {@code file} holds plus what this engine has counted since it last folded. */
    public static Snapshot snapshot(Path file) {
        Stored stored = read(file);
        List<Row> rows = new ArrayList<>();
        for (Tier t : Tier.values()) {
            long[] f = stored.fields(t);
            rows.add(new Row(
                    t,
                    f[HITS] + live(t, HITS),
                    f[STORES] + live(t, STORES),
                    f[RESTORED] + live(t, RESTORED),
                    f[STORED] + live(t, STORED)));
        }
        return new Snapshot(stored.since(), rows);
    }

    /** Fold what this engine has counted into {@code file}, and start counting again from zero. */
    public static synchronized void flush(Path file) {
        long[] taken = new long[LIVE.length()];
        boolean any = false;
        for (int i = 0; i < taken.length; i++) {
            taken[i] = LIVE.getAndSet(i, 0);
            any |= taken[i] != 0;
        }
        if (!any) return;
        Stored stored = read(file);
        StringBuilder out = new StringBuilder("since = " + stored.since() + "\n");
        for (Tier t : Tier.values()) {
            long[] f = stored.fields(t);
            out.append(t.key()).append(" = [");
            for (int i = 0; i < FIELDS; i++) {
                if (i > 0) out.append(", ");
                out.append(f[i] + taken[t.ordinal() * FIELDS + i]);
            }
            out.append("]\n");
        }
        try {
            Files.createDirectories(file.getParent());
            AtomicWrites.replace(file, out.toString());
        } catch (IOException | RuntimeException e) {
            // Counts that could not be written are put back for the next fold.
            for (int i = 0; i < taken.length; i++) LIVE.addAndGet(i, taken[i]);
            Log.debug("cache stats not written: " + file, e);
        }
    }

    private static long live(Tier t, int field) {
        return LIVE.get(t.ordinal() * FIELDS + field);
    }

    private record Stored(long since, long[][] fields) {
        long[] fields(Tier t) {
            return fields[t.ordinal()];
        }
    }

    private static Stored read(Path file) {
        long[][] fields = new long[Tier.values().length][FIELDS];
        long since = Clock.SYSTEM.millis();
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (key.equals("since")) {
                        since = Long.parseLong(value);
                        continue;
                    }
                    for (Tier t : Tier.values()) {
                        if (!t.key().equals(key)) continue;
                        String[] parts = value.replace("[", "").replace("]", "").split(",");
                        for (int i = 0; i < Math.min(FIELDS, parts.length); i++) {
                            fields[t.ordinal()][i] = Long.parseLong(parts[i].trim());
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException torn) {
            // A torn or unreadable file starts the totals over; it holds nothing a build needs.
        }
        return new Stored(since, fields);
    }

    /** Test seam: forget what this engine has counted. */
    static void resetForTest() {
        for (int i = 0; i < LIVE.length(); i++) LIVE.set(i, 0);
    }
}
