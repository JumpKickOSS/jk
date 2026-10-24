// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * How often, and how lately, each entry of a store has been used: the signal a collector needs
 * beyond size and age. An entry used on twenty different days is a dependency of the work being done;
 * one used once is likely a leftover of something that has moved on.
 *
 * <p><b>Hot and cold.</b> Recording a use ({@link #touch}) is a map lookup. The hot map holds the
 * keys this process touched within {@link #EVERY_MILLIS} and is cleared at {@link #HOT_LIMIT}
 * entries, so its heap is bounded whatever the store holds. New uses are buffered and appended as
 * one {@code write} to the journal ({@code <dir>/journal}), one line per use, by whichever process
 * saw them: the client, the engine and test JVMs all append to the same file, which {@code
 * O_APPEND} keeps whole per write. Nothing is read back until a collector asks ({@link #load}).
 *
 * <p><b>Bounded.</b> The journal is folded into {@code <dir>/snapshot} — one line per key: first
 * use, last use, uses, distinct days — by {@link #compact}, which a collector runs every pass and
 * which a writer runs itself when the journal passes {@link #JOURNAL_LIMIT}. A key whose entry is
 * gone is dropped by that fold, so the snapshot tracks the store, not its history.
 *
 * <p>A key with no record is not an error: a collector falls back to the entry's own mtime.
 */
public final class AccessLedger {

    /** A key this process recorded this recently is not recorded again. */
    public static final long EVERY_MILLIS = 10L * 60 * 1000;

    /** Keys the hot map holds before it is cleared; the journal already has them. */
    static final int HOT_LIMIT = 8192;

    /** Buffered uses written together once this many wait. */
    static final int FLUSH_AT = 64;

    /** A journal past this many bytes is folded by the writer that noticed. */
    static final long JOURNAL_LIMIT = 4L << 20;

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    private static final ConcurrentHashMap<Path, AccessLedger> OPEN = new ConcurrentHashMap<>();

    /** What the ledger knows of one key. {@code days} counts the distinct UTC days it was used on. */
    public record Use(long firstMillis, long lastMillis, int uses, int days) {}

    private final Path dir;
    private final ConcurrentHashMap<String, Long> hot = new ConcurrentHashMap<>();
    private final List<String> pending = new ArrayList<>();

    static {
        // A short-lived client buffers fewer than FLUSH_AT uses; they are written as it exits.
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> OPEN.values().forEach(AccessLedger::flush), "access-ledger"));
    }

    private AccessLedger(Path dir) {
        this.dir = dir;
    }

    /** The ledger kept in {@code dir} (created on the first write); one instance per directory per process. */
    public static AccessLedger at(Path dir) {
        return OPEN.computeIfAbsent(dir.toAbsolutePath().normalize(), AccessLedger::new);
    }

    private Path journal() {
        return dir.resolve("journal");
    }

    private Path snapshot() {
        return dir.resolve("snapshot");
    }

    /** Record that {@code key} is in use now. Never throws: a ledger that cannot write loses a hint, not a build. */
    public void touch(String key) {
        touch(key, Clock.SYSTEM.millis());
    }

    /** Record that {@code key} was in use at {@code now}, a clock the caller supplies. */
    public void touch(String key, long now) {
        Long last = hot.get(key);
        if (last != null && now - last < EVERY_MILLIS) return;
        if (hot.size() >= HOT_LIMIT) hot.clear();
        hot.put(key, now);
        List<String> batch = null;
        synchronized (pending) {
            pending.add(now + "\t" + key + "\n");
            if (pending.size() >= FLUSH_AT) {
                batch = new ArrayList<>(pending);
                pending.clear();
            }
        }
        if (batch != null) append(batch);
    }

    /** Write what is buffered. Called when a process is about to exit and before a collector reads. */
    public void flush() {
        List<String> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        append(batch);
    }

    private void append(List<String> lines) {
        try {
            Files.createDirectories(dir);
            byte[] bytes = String.join("", lines).getBytes(StandardCharsets.UTF_8);
            Files.write(journal(), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (Files.size(journal()) > JOURNAL_LIMIT) compact(key -> true);
        } catch (IOException | RuntimeException unwritable) {
            // a lost use only shortens a countdown to the entry's own mtime
        }
    }

    /** Everything recorded, folded: the snapshot with the journal applied. */
    public Map<String, Use> load() {
        flush();
        Map<String, long[]> folded = new HashMap<>();
        readInto(snapshot(), folded, true);
        readInto(journal(), folded, false);
        Map<String, Use> uses = new HashMap<>();
        folded.forEach((key, v) -> uses.put(key, new Use(v[0], v[1], (int) v[2], (int) v[3])));
        return uses;
    }

    private static void readInto(Path file, Map<String, long[]> into, boolean snapshot) {
        List<String> lines;
        try {
            if (!Files.isRegularFile(file)) return;
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return;
        }
        for (String line : lines) {
            String[] f = line.split("\t");
            try {
                if (snapshot && f.length == 5) {
                    // key, first, last, uses, days
                    into.put(f[0], new long[] {
                        Long.parseLong(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4])
                    });
                } else if (!snapshot && f.length == 2) {
                    apply(into, f[1], Long.parseLong(f[0]));
                }
            } catch (NumberFormatException damaged) {
                // a torn line is skipped; every other line is whole
            }
        }
    }

    private static void apply(Map<String, long[]> into, String key, long at) {
        long[] v = into.get(key);
        if (v == null) {
            into.put(key, new long[] {at, at, 1, 1});
            return;
        }
        if (at / DAY_MILLIS != v[1] / DAY_MILLIS) v[3]++;
        v[0] = Math.min(v[0], at);
        v[1] = Math.max(v[1], at);
        v[2]++;
    }

    /**
     * Fold the journal into the snapshot, keeping only the keys {@code live} accepts, and empty the
     * journal. Appends racing the fold are not lost: the journal is renamed away first, so a writer
     * that opens it afterwards starts a new one.
     */
    public synchronized void compact(Predicate<String> live) {
        flush();
        try {
            // A fold that died after the rename left its half behind: it is folded in first.
            Path taken = dir.resolve("journal.folding");
            Map<String, long[]> folded = new HashMap<>();
            readInto(snapshot(), folded, true);
            readInto(taken, folded, false);
            if (Files.isRegularFile(journal())) {
                Files.move(journal(), taken, StandardCopyOption.REPLACE_EXISTING);
                readInto(taken, folded, false);
            }
            folded.keySet().removeIf(key -> !live.test(key));
            StringBuilder out = new StringBuilder();
            folded.forEach((key, v) -> out.append(key)
                    .append('\t')
                    .append(v[0])
                    .append('\t')
                    .append(v[1])
                    .append('\t')
                    .append(v[2])
                    .append('\t')
                    .append(v[3])
                    .append('\n'));
            Files.createDirectories(dir);
            AtomicWrites.replace(snapshot(), out.toString());
            Files.deleteIfExists(taken);
        } catch (IOException | RuntimeException unwritable) {
            // the next fold retries; the journal is still whole
        }
    }
}
