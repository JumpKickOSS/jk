// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * A {@link DirWatch} that lists every registered directory each interval and reports what differs
 * from the last listing. It hears a change another machine made to a network volume, which inotify
 * and FSEvents, reporting only what passed through this machine's kernel, never do.
 */
final class PollingDirWatch implements DirWatch {

    /** How often each directory is listed: the JDK's own polling watch's interval. */
    static final long INTERVAL_MILLIS = 2_000;

    /** What a listing keeps of one entry; a directory's own timestamp is not a change to it. */
    private record Stamp(boolean dir, @Nullable FileTime modified, long size) {}

    private final Clock clock;
    private final long intervalNanos;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition closing = lock.newCondition();

    /** Each registered directory, as registered, with its entries as last listed. */
    private final Map<Path, Map<Path, Stamp>> dirs = new LinkedHashMap<>();

    private long nextScan;
    private boolean closed;

    PollingDirWatch(Clock clock, long intervalNanos) {
        this.clock = clock;
        this.intervalNanos = intervalNanos;
        this.nextScan = clock.nanos() + intervalNanos;
    }

    static PollingDirWatch open() {
        return new PollingDirWatch(Clock.SYSTEM, TimeUnit.MILLISECONDS.toNanos(INTERVAL_MILLIS));
    }

    @Override
    public void register(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Map<Path, Stamp> entries = list(dir);
        lock.lock();
        try {
            if (entries != null) dirs.put(dir, entries);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean watches(Path dir) {
        lock.lock();
        try {
            return dirs.containsKey(dir.toAbsolutePath().normalize());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Change> take() throws InterruptedException {
        while (true) {
            List<Change> changes = poll(Long.MAX_VALUE);
            if (changes != null) return changes;
        }
    }

    @Override
    public @Nullable List<Change> poll(long nanos) throws InterruptedException {
        long start = clock.nanos();
        lock.lock();
        try {
            while (true) {
                if (closed) throw new ClosedWatchServiceException();
                long now = clock.nanos();
                if (now - nextScan >= 0) {
                    nextScan = now + intervalNanos;
                    List<Change> changes = scan();
                    if (!changes.isEmpty()) return changes;
                }
                long left = nanos - (now - start);
                if (left <= 0) return null;
                closing.awaitNanos(Math.min(left, nextScan - now));
            }
        } finally {
            lock.unlock();
        }
    }

    /** Every change since the last listing, a deleted directory forgotten with its entries. */
    private List<Change> scan() {
        List<Change> out = new ArrayList<>();
        for (Iterator<Map.Entry<Path, Map<Path, Stamp>>> it = dirs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, Map<Path, Stamp>> e = it.next();
            Path dir = e.getKey();
            Map<Path, Stamp> before = e.getValue();
            Map<Path, Stamp> now = list(dir);
            for (Path entry : before.keySet()) {
                if (now == null || !now.containsKey(entry)) {
                    out.add(new Change(StandardWatchEventKinds.ENTRY_DELETE, dir, entry));
                }
            }
            if (now == null) {
                it.remove();
                continue;
            }
            for (Map.Entry<Path, Stamp> entry : now.entrySet()) {
                Stamp was = before.get(entry.getKey());
                if (was == null) {
                    out.add(new Change(StandardWatchEventKinds.ENTRY_CREATE, dir, entry.getKey()));
                } else if (!entry.getValue().dir() && !entry.getValue().equals(was)) {
                    out.add(new Change(StandardWatchEventKinds.ENTRY_MODIFY, dir, entry.getKey()));
                }
            }
            e.setValue(now);
        }
        return out;
    }

    /** The entries of {@code dir}; null when it is no longer a directory that can be listed. */
    private static @Nullable Map<Path, Stamp> list(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        Map<Path, Stamp> out = new HashMap<>();
        try {
            PathUtil.forEachChild(dir, (entry, attrs) -> {
                out.put(
                        entry,
                        attrs.isDirectory()
                                ? new Stamp(true, null, 0)
                                : new Stamp(false, Objects.requireNonNull(attrs.lastModifiedTime()), attrs.size()));
                return true;
            });
        } catch (IOException e) {
            return null;
        }
        return out;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            closing.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
