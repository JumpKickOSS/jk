// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.host.Os;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The directories a {@link SourceWatch} watches, each for changes to its own entries, and the
 * changes they report in batches. On macOS FSEvents reports them ({@link FsEventsWatch}); elsewhere,
 * on a volume that is not local, or when FSEvents cannot be bound, the JDK's {@link
 * java.nio.file.WatchService} does ({@link JdkDirWatch}), which on macOS polls every 2 s.
 */
interface DirWatch extends Closeable {

    /** Watch the entries of {@code dir}; nothing when it is not a directory. */
    void register(Path dir) throws IOException;

    /** Whether {@code dir} is watched now: registered, and not deleted since. */
    boolean watches(Path dir);

    /** Block until a batch of changes arrives. */
    List<Change> take() throws InterruptedException;

    /** The next batch, waiting up to {@code nanos}; null when none arrives in time. */
    @Nullable
    List<Change> poll(long nanos) throws InterruptedException;

    /**
     * One change: {@code kind} to {@code entry} of the watched {@code dir}. An {@link
     * StandardWatchEventKinds#OVERFLOW} names neither: changes were lost, and which is unknown.
     */
    record Change(
            WatchEvent.Kind<?> kind,
            @Nullable Path dir,
            @Nullable Path entry) {
        static final Change OVERFLOW = new Change(StandardWatchEventKinds.OVERFLOW, null, null);
    }

    /**
     * The host's watch over {@code dirs}: FSEvents on macOS when it binds and every one is on a local
     * volume, else the JDK's. FSEvents reports only changes made through this machine's kernel, so a
     * network share another machine writes to is polled.
     */
    static DirWatch open(Collection<Path> dirs) throws IOException {
        return open(dirs, FsEventsWatch::local);
    }

    /** {@link #open(Collection)}, a directory's volume told local by {@code local}. */
    static DirWatch open(Collection<Path> dirs, Predicate<Path> local) throws IOException {
        if (Os.isDarwin() && dirs.stream().allMatch(local)) {
            DirWatch fsEvents = FsEventsWatch.open();
            if (fsEvents != null) return fsEvents;
        }
        return JdkDirWatch.open();
    }
}
