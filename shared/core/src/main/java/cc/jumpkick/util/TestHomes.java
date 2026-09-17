// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@code JK_HOME} a forked test JVM runs against: jk's whole layout, throwaway, and
 * <b>outside the project under test</b>.
 *
 * <p><b>Why not under the project.</b> This home carries jk's whole layout, {@code
 * store/templates/<key>/} among the rest. A {@code git} command handed a path inside a source tree
 * that has stopped existing resolves to the repository enclosing it, so a cache refresh there is a
 * {@code fetch --depth 1} and {@code reset --hard} against the developer's checkout. Pinning {@code
 * --git-dir} is what stops the command; keeping the home out of the tree is what leaves such a
 * command nothing to aim at. A sibling of the project does not satisfy it either: a workspace
 * member's enclosing repository is the workspace root.
 *
 * <p><b>Why under the product home.</b> {@code <home>/test-homes} is short on every platform (macOS
 * {@code TMPDIR} is deep and resolves through {@code /var} → {@code /private/var}; Windows stops at
 * 260 characters), survives a reboot, and moves with {@code JK_HOME}: an engine already running in a
 * sandbox keeps its own sandboxes inside it rather than writing into the developer's real home. It
 * is <b>warm on purpose</b> — it holds a fetched store, and a per-run home would re-download every
 * dependency on every {@code jk test}.
 *
 * <p><b>Cleanup.</b> Warm is not unbounded. {@link #prepare} stamps the slot it hands out with the
 * module's path, and every launch reaps every slot whose module directory no longer exists — a
 * deleted worktree, a fixture project a suite made under a temp dir and removed — every slot not
 * stamped within {@link #KEEP_DAYS} days, then the least recently stamped slots until the root fits
 * {@link #KEEP_BYTES} — before the run, so a project that was deleted or renamed cannot leave one
 * behind for ever, and a root that several agents' engines share reaches a steady state within one
 * launch of a slot going stale. The pass is cheap where it can be: a launch within
 * {@link #REAP_EVERY_MILLIS} of a pass that removed nothing and left the root under the cap skips
 * its own, and a slot's size is walked once and recorded ({@link #SIZE}) until the slot is used
 * again, so a root of a thousand idle slots costs a listing, not a thousand walks. A launch marks
 * every slot it reads with
 * a hold ({@link #hold}: a file under {@code .holds} naming its pid and start time, released when
 * the suite exits), and the reaper never removes a held slot — several gates share one machine and
 * one of them launching must not pull the dependency jars out from under another. A hold whose
 * process has exited is dropped as it is read. A slot stamped within {@link #HOLD_HOURS} is kept
 * from the byte cap as well, the fallback for a launch that could not write its hold. {@code jk
 * clean} deletes this module's slot outright. Nothing is deleted after a run: that is the warmth.
 *
 * <p><b>The shared local m2.</b> A workspace's test JVMs share one Maven local repository, {@code
 * <workspace slot>/test-m2}: the sandbox store answers a lock row from it, so it is the classpath a
 * suite runs against. {@link #prepareSlot} stamps that slot at every launch exactly like a home,
 * which is what keeps the reaper of a concurrent launch off it.
 *
 * <p><b>A slot, not just a home.</b> {@code JK_HOME} is {@code <slot>/home} rather than the slot
 * itself because the launcher derives the shared test cache as a <em>sibling</em> of {@code JK_HOME},
 * so wiping the home does not wipe the cache. Flattening this would turn a per-module cache into one
 * shared by every module on the machine.
 */
public final class TestHomes {

    /** Under the product home ({@link JkDirs#home()}), so {@code JK_HOME} relocates the sandboxes too. */
    public static final String DIR = "test-homes";

    /** A slot untouched for this long belongs to a project that has moved on. */
    static final int KEEP_DAYS = 14;

    /** Bytes the root may hold before the least recently used slots go. */
    static final long KEEP_BYTES = 2L << 30;

    /** A slot stamped this recently is held: a gate that launched it may still be running. */
    static final int HOLD_HOURS = 24;

    /** {@code <slot>/.used-at}: first line the module's absolute path, mtime the last hand-out. */
    private static final String STAMP = ".used-at";

    /**
     * The slot's byte count as of its last sizing, beside the stamp. A record at least as new as the
     * stamp stands in for the walk; a launch re-stamps the slot and a closed hold removes the
     * record, so a slot is walked again only after it has been used.
     */
    static final String SIZE = ".bytes";

    /** {@code <slot>/.holds/<pid>-<n>}, body {@code <pid> <start epoch millis>}: one per live launch. */
    private static final String HOLDS = ".holds";

    /** A launch this soon after a pass that found nothing to reap, under the cap, skips its own. */
    static final long REAP_EVERY_MILLIS = 10L * 60 * 1000;

    /** What one reap of a root found: when it ran, the bytes it left, how many slots it removed. */
    record Pass(long atMillis, long bytes, int removed) {
        /** True when the root is settled: nothing went and what stayed fits the cap. */
        boolean quiet(long capBytes) {
            return removed == 0 && bytes <= capBytes;
        }
    }

    /** The last pass over each root; keyed by root because {@code JK_HOME} moves it. */
    private static final ConcurrentHashMap<Path, Pass> PASSES = new ConcurrentHashMap<>();

    private static final AtomicLong HOLD_SEQ = new AtomicLong();

    private TestHomes() {}

    /** {@code <home>/test-homes}: under {@code JK_HOME} when set, else {@code ~/.jk}. */
    public static Path root() {
        return JkDirs.home().resolve(DIR);
    }

    /**
     * This module's slot: {@code <root>/<key>}, holding the home and its sibling caches.
     *
     * <p>Keyed by a digest of the module's real path and nothing else: readable names spend the
     * Windows path budget, and two checkouts of one repository have the same module names but must
     * not share a store.
     */
    public static Path slotFor(Path moduleDir) {
        return root().resolve(keyFor(moduleDir));
    }

    /**
     * The {@code JK_HOME} for this module. Pure — no directory is created and nothing is stamped, because
     * the caller that asks what the environment <em>is</em> runs long before a worker needs it to exist.
     * {@link #prepare} is the one that touches the disk.
     */
    public static Path pathFor(Path moduleDir) {
        return slotFor(moduleDir).resolve("home");
    }

    /** {@link #pathFor} with the directory created and the slot stamped, stale slots reaped first. */
    public static Path prepare(Path moduleDir) throws IOException {
        return prepare(moduleDir, Clock.SYSTEM);
    }

    /**
     * {@link #prepare} against a supplied clock. The reap window is the only time this class reads,
     * and it decides whether a slot is deleted — so a test settles it rather than arranging file
     * timestamps around whatever now happens to be.
     */
    public static Path prepare(Path moduleDir, Clock clock) throws IOException {
        Path home = prepareSlot(moduleDir, clock).resolve("home");
        Files.createDirectories(home);
        return home;
    }

    /**
     * {@code dir}'s slot, created and stamped as in use, stale slots reaped first. The workspace's
     * shared {@code test-m2} lives in the workspace root's slot, which holds no home: it is stamped
     * through here so the reaper reads it as a slot in use rather than as a leftover.
     */
    public static Path prepareSlot(Path dir) throws IOException {
        return prepareSlot(dir, Clock.SYSTEM);
    }

    /** {@link #prepareSlot(Path)} against a supplied clock. */
    public static Path prepareSlot(Path dir, Clock clock) throws IOException {
        reapIfDue(root(), clock.millis(), KEEP_BYTES);
        Path slot = slotFor(dir);
        Files.createDirectories(slot);
        stamp(slot, dir);
        return slot;
    }

    /**
     * Reap {@code root} unless its last pass, within {@link #REAP_EVERY_MILLIS}, was quiet: the
     * listing and sizing of every slot is what a launch on a busy machine is spared. A pass that
     * removed something, or left the root over the cap, is followed by a full pass at the next
     * launch. True when a pass ran.
     */
    static boolean reapIfDue(Path root, long nowMillis, long capBytes) {
        Pass last = PASSES.get(root);
        if (last != null && nowMillis - last.atMillis() < REAP_EVERY_MILLIS && last.quiet(capBytes)) return false;
        PASSES.put(root, reapStale(root, nowMillis, capBytes));
        return true;
    }

    /**
     * The 12-hex key {@link #slotFor} uses; exposed so {@code jk clean} can find the same one. Of the
     * real path when the module exists, so a module reached through a link keys as the directory itself.
     */
    public static String keyFor(Path moduleDir) {
        return Hashing.sha256Hex(realPath(moduleDir).toString()).substring(0, 12);
    }

    private static Path realPath(Path moduleDir) {
        Path abs = moduleDir.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException notYetOnDisk) {
            // The absolute form is the identity until the directory exists.
            return abs;
        }
    }

    /**
     * Whether the stamp names a module directory that does not exist. A stamp naming none — a bare
     * note, or one that will not read — leaves the slot to its age.
     */
    static boolean moduleGone(Path slot) {
        try {
            String first = Files.readString(slot.resolve(STAMP))
                    .lines()
                    .findFirst()
                    .orElse("")
                    .strip();
            if (first.isEmpty()) return false;
            Path module = Path.of(first);
            return module.isAbsolute() && !Files.isDirectory(module);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Record that this slot is in use by {@code moduleDir}, so the next run's reap leaves it alone
     * for as long as the module exists. Best effort.
     */
    static void stamp(Path slot, Path moduleDir) {
        try {
            Files.createDirectories(slot);
            Files.writeString(
                    slot.resolve(STAMP),
                    realPath(moduleDir) + "\njk test sandbox for the module above; see cc.jumpkick.util.TestHomes\n");
        } catch (IOException | RuntimeException e) {
            // A missing stamp costs this home an early reap, never a failed build.
            Log.debug("stamp: A missing stamp costs this home an early reap, never a failed build", e);
        }
    }

    /** Slots marked as read by a live launch until {@link #close}; see {@link TestHomes#hold}. */
    public static final class Hold implements AutoCloseable {
        private final List<Path> files;

        private Hold(List<Path> files) {
            this.files = files;
        }

        @Override
        public void close() {
            for (Path file : files) {
                try {
                    Files.deleteIfExists(file);
                    // The suite wrote into this slot while it ran: the next reap measures it afresh.
                    Path holds = file.getParent();
                    Path slot = holds == null ? null : holds.getParent();
                    if (slot != null) Files.deleteIfExists(slot.resolve(SIZE));
                } catch (IOException | RuntimeException e) {
                    // A hold left behind names this live process; it lapses when the process does.
                    Log.debug("Hold.close: A hold left behind lapses when this process exits", e);
                }
            }
        }
    }

    /**
     * Mark each of {@code slots} as read by this process — a suite is about to run against its jars —
     * until the returned hold is closed. Best effort: a hold that could not be written leaves the slot
     * to the stamp's hold window.
     */
    public static Hold hold(Path... slots) {
        ProcessHandle self = ProcessHandle.current();
        String body = self.pid() + " " + startMillis(self) + "\n";
        List<Path> files = new ArrayList<>();
        for (Path slot : slots) {
            try {
                Path dir = Files.createDirectories(slot.resolve(HOLDS));
                Path file = dir.resolve(self.pid() + "-" + Long.toString(HOLD_SEQ.incrementAndGet(), 36));
                Files.writeString(file, body);
                files.add(file);
            } catch (IOException | RuntimeException e) {
                Log.debug("hold: A missing hold leaves the slot to the stamp's hold window, never a failed build", e);
            }
        }
        return new Hold(List.copyOf(files));
    }

    private static long startMillis(ProcessHandle process) {
        return process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    /** Whether a hold under {@code slot} names a live process. A hold from one that has exited is removed. */
    static boolean held(Path slot) {
        boolean[] live = {false};
        try {
            PathUtil.forEachChild(slot.resolve(HOLDS), (file, attrs) -> {
                if (!attrs.isRegularFile()) return true;
                if (holdIsLive(file)) {
                    live[0] = true;
                } else {
                    Files.deleteIfExists(file);
                }
                return true;
            });
        } catch (IOException | RuntimeException e) {
            // A holds directory that will not list is read as unheld; the stamp's window still applies.
            Log.debug("held: A holds directory that will not list is read as unheld", e);
        }
        return live[0];
    }

    private static boolean holdIsLive(Path file) {
        try {
            String[] parts = Files.readString(file).trim().split("\\s+");
            long pid = Long.parseLong(parts[0]);
            long start = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
            return isLive(pid, start);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * A process with this pid is running and started when the hold says, within a second — a pid
     * the OS has reused since belongs to someone else. A start time of zero on either side (an OS
     * that reports none) leaves the pid alone to answer.
     */
    static boolean isLive(long pid, long startMillis) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        if (handle.isEmpty()) return false;
        long started = startMillis(handle.get());
        return started == 0L || startMillis == 0L || Math.abs(started - startMillis) <= 1000;
    }

    /**
     * A slot, when it was last handed out (the stamp's mtime, or the directory's without one), its
     * size, and whether a live launch holds it.
     */
    private record Slot(Path dir, long usedMillis, long bytes, boolean held) {}

    /**
     * Delete every slot whose module directory is gone or whose stamp is older than
     * {@link #KEEP_DAYS} — and every entry that carries no stamp at all, since nothing else writes
     * here — then the least recently stamped survivors until the rest fit {@code capBytes}. Never a
     * slot a live launch holds, and never one stamped within {@link #HOLD_HOURS} unless its module is
     * gone. Returns the pass: how many were removed and the bytes that stayed. Best effort: a slot
     * another process still has open is left for the run after this one.
     */
    static Pass reapStale(Path root, long nowMillis, long capBytes) {
        long cutoff = nowMillis - KEEP_DAYS * 24L * 60 * 60 * 1000;
        long held = nowMillis - HOLD_HOURS * 60L * 60 * 1000;
        int removed = 0;
        List<Slot> fresh = new ArrayList<>();
        long total = 0;
        try {
            List<Path> stale = new ArrayList<>();
            PathUtil.forEachChild(root, (slot, attrs) -> {
                // A symlink arrives as a non-directory here, so a link planted in the root is skipped
                // rather than followed out of it. The listing attributes also answer the unstamped case.
                if (!attrs.isDirectory()) return true;
                long used = PathUtil.stat(slot.resolve(STAMP))
                        .map(st -> st.lastModifiedTime().toMillis())
                        .orElseGet(() -> attrs.lastModifiedTime().toMillis());
                boolean inUse = held(slot);
                if ((used <= cutoff || moduleGone(slot)) && !inUse) {
                    stale.add(slot);
                } else {
                    fresh.add(new Slot(slot, used, size(slot, used), inUse));
                }
                return true;
            });
            for (Path slot : stale) {
                if (delete(slot)) removed++;
            }
        } catch (IOException | RuntimeException e) {
            // The reap is hygiene, never the reason a build fails.
            Log.debug("reapStale: The reap is hygiene, never the reason a build fails", e);
        }
        for (Slot slot : fresh) total += slot.bytes();
        // Oldest first: the stamp is rewritten on every use, so it is a real use clock.
        fresh.sort(Comparator.comparingLong(Slot::usedMillis));
        for (Slot slot : fresh) {
            if (total <= capBytes) break;
            // A live launch reads this slot, or one inside the hold window may still be running.
            if (slot.held() || slot.usedMillis() > held) continue;
            if (delete(slot.dir())) {
                removed++;
                total -= slot.bytes();
            }
        }
        return new Pass(nowMillis, total, removed);
    }

    private static boolean delete(Path slot) {
        try {
            PathUtil.deleteRecursivelyOrThrow(slot);
            return true;
        } catch (IOException | RuntimeException heldOrDenied) {
            // Held by a concurrent run, or a permission we do not have. Next time.
            return false;
        }
    }

    /**
     * Bytes under {@code slot}: the {@link #SIZE} record when it is at least as new as the slot's
     * last use at {@code usedMillis}, else a walk, recorded for the next pass. A root of many idle
     * slots is walked once, not at every launch.
     */
    static long size(Path slot, long usedMillis) {
        Path record = slot.resolve(SIZE);
        Optional<Long> recorded = PathUtil.stat(record)
                .filter(st -> st.lastModifiedTime().toMillis() >= usedMillis)
                .flatMap(st -> readSize(record));
        if (recorded.isPresent()) return recorded.get();
        long bytes = walk(slot);
        try {
            Files.writeString(record, Long.toString(bytes));
        } catch (IOException | RuntimeException e) {
            // An unrecorded size costs the next pass a walk, never a failed build.
            Log.debug("size: An unrecorded size costs the next pass a walk", e);
        }
        return bytes;
    }

    private static Optional<Long> readSize(Path record) {
        try {
            return Optional.of(Long.parseLong(Files.readString(record).strip()));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Bytes of regular files under {@code slot}, the {@link #SIZE} record itself aside; links are
     * counted as themselves, never followed.
     */
    private static long walk(Path slot) {
        long[] total = {0};
        try {
            PathUtil.forEachRegularFile(slot, (file, attrs) -> {
                Path name = file.getFileName();
                if (name == null || !SIZE.equals(name.toString())) total[0] += attrs.size();
            });
        } catch (IOException | RuntimeException e) {
            // A slot that will not list is sized by what did list.
            Log.debug("size: A slot that will not list is sized by what did list", e);
        }
        return total[0];
    }
}
