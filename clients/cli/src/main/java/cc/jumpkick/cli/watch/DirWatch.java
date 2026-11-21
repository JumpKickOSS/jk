// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The directories a {@link SourceWatch} watches, each for changes to its own entries, and the
 * changes they report in batches. On a network volume a poll reports them ({@link PollingDirWatch});
 * else on macOS FSEvents does ({@link FsEventsWatch}), and elsewhere, or when FSEvents cannot be
 * bound, the JDK's {@link java.nio.file.WatchService} ({@link JdkDirWatch}), which on macOS polls
 * every 2 s.
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
     * The host's watch over {@code dirs}: a poll when one is on a network volume, since inotify and
     * FSEvents report only changes made through this machine's kernel and never hear what another
     * machine writes to a share; else FSEvents on macOS when it binds, else the JDK's.
     */
    static DirWatch open(Collection<Path> dirs) throws IOException {
        return open(dirs, DirWatch::local);
    }

    /** {@link #open(Collection)}, a directory's volume told local by {@code local}. */
    static DirWatch open(Collection<Path> dirs, Predicate<Path> local) throws IOException {
        if (!dirs.stream().allMatch(local)) return PollingDirWatch.open();
        if (Os.isDarwin()) {
            DirWatch fsEvents = FsEventsWatch.open();
            if (fsEvents != null) return fsEvents;
        }
        return JdkDirWatch.open();
    }

    /**
     * Whether {@code dir}, or the nearest directory above it that exists, is on a volume whose
     * changes this machine's watch hears. On Windows every volume is: a share reports what other
     * machines write to it.
     */
    static boolean local(Path dir) {
        if (Os.isDarwin()) return FsEventsWatch.local(dir);
        if (!Os.isLinux()) return true;
        Path at = dir.toAbsolutePath().normalize();
        while (at != null && !Files.exists(at)) at = at.getParent();
        if (at == null) return true;
        try {
            return !NETWORK_TYPES.contains(Files.getFileStore(at).type());
        } catch (IOException e) {
            Log.debug("no file store for " + at, e);
            return true;
        }
    }

    /**
     * The Linux mount types whose files other machines write: NFS, SMB, AFS, Ceph, Lustre, GlusterFS,
     * SSHFS, and the 9p and drvfs mounts through which WSL reads the Windows drives.
     */
    Set<String> NETWORK_TYPES = Set.of(
            "nfs",
            "nfs4",
            "cifs",
            "smb3",
            "smbfs",
            "afs",
            "ceph",
            "lustre",
            "glusterfs",
            "fuse.glusterfs",
            "fuse.sshfs",
            "9p",
            "drvfs");
}
