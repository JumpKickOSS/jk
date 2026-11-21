// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.host.Os;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The directories a {@link SourceWatch} watches, each for changes to its own entries, and the
 * changes they report in batches. On macOS FSEvents reports them ({@link FsEventsWatch}); elsewhere,
 * or when FSEvents cannot be bound, the JDK's {@link java.nio.file.WatchService} does ({@link
 * JdkDirWatch}), which on macOS polls every 2 s.
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

    /** The host's watch: FSEvents on macOS when it binds, else the JDK's. */
    static DirWatch open() throws IOException {
        if (Os.isDarwin()) {
            DirWatch fsEvents = FsEventsWatch.open();
            if (fsEvents != null) return fsEvents;
        }
        return JdkDirWatch.open();
    }
}
