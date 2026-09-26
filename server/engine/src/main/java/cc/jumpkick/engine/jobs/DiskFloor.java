// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Free space a job needs before it writes. The floor is the larger of 1 GiB and 2% of the volume,
 * and never more than 2 GiB. Below it the job waits briefly — another job may be about to free
 * the space — then refuses, naming the path. A check that cannot be read does not refuse.
 */
public final class DiskFloor {

    /** One gibibyte. */
    public static final long GIB = 1L << 30;

    private static final long MIB = 1L << 20;

    /** How long a short volume is rechecked before the job is refused. */
    public static final long WAIT_MS = 15_000;

    private static final long POLL_MS = 500;

    private DiskFloor() {}

    /** A volume under its floor. */
    public record Shortage(Path path, long usableBytes, long floorBytes) {

        /** {@code not enough free space on /path: 400 MiB free, need 1.0 GiB}. */
        public String message() {
            return "not enough free space on " + path + ": " + format(usableBytes) + " free, need "
                    + format(floorBytes);
        }
    }

    /** How a wait ended. {@code shortage} is set only when the volume was still short. */
    public record Outcome(boolean halted, @Nullable Shortage shortage) {

        static Outcome ok() {
            return new Outcome(false, null);
        }

        static Outcome halt() {
            return new Outcome(true, null);
        }

        static Outcome shortOf(Shortage shortage) {
            return new Outcome(false, shortage);
        }
    }

    /** Usable and total bytes of one path. Tests substitute a fixed volume. */
    public interface Probe {
        long usable(Path path);

        long total(Path path);

        /** Same string for paths on one filesystem, so a job does not check it twice. */
        String volume(Path path);
    }

    /**
     * The floor for a volume of {@code totalBytes}: at least 1 GiB, at least 2% of the volume, and
     * at most 2 GiB.
     */
    public static long floorBytes(long totalBytes) {
        if (totalBytes <= 0) return GIB;
        long twoPercent = Math.max(0, totalBytes / 50);
        return Math.min(2 * GIB, Math.max(GIB, twoPercent));
    }

    /** The job directory, its {@link BuildLayout#TARGET} when that exists, and the artifact store. */
    public static List<Path> paths(@Nullable String dir) {
        List<Path> out = new ArrayList<>();
        if (dir != null && !dir.isBlank()) {
            Path root = Path.of(dir);
            out.add(root);
            Path target = root.resolve(BuildLayout.TARGET);
            if (Files.exists(target)) out.add(target);
        }
        try {
            out.add(JkDirs.store());
        } catch (RuntimeException e) {
            Log.debug("disk floor: store path unreadable", e);
        }
        return out;
    }

    /** Wait up to {@link #WAIT_MS}, then refuse if a path is still under the floor. */
    public static Outcome await(List<Path> paths, BooleanSupplier stop) {
        return await(paths, FileStores.INSTANCE, stop, WAIT_MS);
    }

    /** As {@link #await(List, BooleanSupplier)} with the probe and the wait a test supplies. */
    public static Outcome await(List<Path> paths, Probe probe, BooleanSupplier stop, long waitMs) {
        long deadline = Clock.SYSTEM.nanos() + Math.max(0, waitMs) * 1_000_000L;
        boolean logged = false;
        while (true) {
            if (stop.getAsBoolean()) return Outcome.halt();
            Shortage shortage = check(paths, probe);
            if (shortage == null) return Outcome.ok();
            if (!logged) {
                Log.info("jk engine: waiting for disk: " + shortage.message());
                logged = true;
            }
            if (Clock.SYSTEM.nanos() >= deadline) return Outcome.shortOf(shortage);
            try {
                Thread.sleep(Math.min(POLL_MS, Math.max(1, waitMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.halt();
            }
        }
    }

    /** The first path under its floor, or {@code null} when every path is fine or unreadable. */
    public static @Nullable Shortage check(List<Path> paths, Probe probe) {
        Map<String, Path> seen = new LinkedHashMap<>();
        for (Path path : paths) {
            Path existing = existing(path);
            if (existing == null) continue;
            String key;
            try {
                key = probe.volume(existing);
            } catch (RuntimeException e) {
                key = existing.toAbsolutePath().toString();
            }
            seen.putIfAbsent(key, existing);
        }
        for (Path path : seen.values()) {
            long total;
            long usable;
            try {
                total = probe.total(path);
                usable = probe.usable(path);
            } catch (RuntimeException e) {
                Log.debug("disk floor: " + path + " unreadable", e);
                continue;
            }
            if (total <= 0 || usable < 0) continue;
            long floor = floorBytes(total);
            if (usable < floor) return new Shortage(path, usable, floor);
        }
        return null;
    }

    private static @Nullable Path existing(Path path) {
        Path cur = path;
        while (cur != null && !Files.exists(cur)) cur = cur.getParent();
        return cur;
    }

    private static String format(long bytes) {
        if (bytes >= GIB) return String.format(Locale.ROOT, "%.1f GiB", bytes / (double) GIB);
        return (bytes / MIB) + " MiB";
    }

    private enum FileStores implements Probe {
        INSTANCE;

        @Override
        public long usable(Path path) {
            try {
                return store(path).getUsableSpace();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public long total(Path path) {
            try {
                return store(path).getTotalSpace();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String volume(Path path) {
            try {
                FileStore store = store(path);
                return store + ":" + store.getTotalSpace();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static FileStore store(Path path) {
            try {
                return Files.getFileStore(path);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
