// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.host.CacheTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A directory standing in for a shared cache: laid out as a jk cache directory (records under
 * {@code actions/keys/}, blobs in its CAS), so two homes on one machine, or two machines sharing a
 * mount, can exercise the remote tier without a service. Experimental and for testing jk itself;
 * selected by {@link RemoteCacheBinding}.
 *
 * <p>It keeps the write rules a shared store keeps: a record whose {@code WRITES} is once (outputs,
 * a verdict, an alias) is never replaced by a second write under its key, and a test marker or a
 * class ledger is replaced by the latest. A directory that is not there is a store that cannot be
 * reached: every call throws, and the tier turns itself off for the build.
 */
public final class DirectoryCacheBackend implements CacheBackend {

    private final Path root;
    private final LocalCacheBackend disk;

    public DirectoryCacheBackend(Path root) {
        this.root = Objects.requireNonNull(root, "root");
        this.disk = new LocalCacheBackend(JkStores.cacheCas(root), CacheTree.ACTIONS.under(root), null);
    }

    @Override
    public String name() {
        return "dir:" + root;
    }

    /** The directory's absolute path: the scope a team's key seed is derived under. */
    @Override
    public String seedScope() {
        return "dir:" + root.toAbsolutePath().normalize();
    }

    @Override
    public Map<String, String> records(Lookup lookup) throws IOException {
        reachable();
        return disk.records(lookup);
    }

    @Override
    public Set<String> missing(Map<String, Long> blobs) throws IOException {
        reachable();
        return disk.missing(blobs);
    }

    @Override
    public void fetch(Map<String, Long> blobs, BlobSink sink) throws IOException {
        reachable();
        disk.fetch(blobs, sink);
    }

    @Override
    public void putBlobs(Map<String, Path> blobs) throws IOException {
        reachable();
        disk.putBlobs(blobs);
    }

    @Override
    public void putRecord(String key, String recordText) throws IOException {
        reachable();
        Optional<String> held = disk.record(key);
        if (held.isPresent() && writtenOnce(held.get(), key)) return;
        disk.putRecord(key, recordText);
    }

    /** Whether {@code text}, held under {@code key}, is a record a second write must not replace. */
    private static boolean writtenOnce(String text, String key) {
        try {
            ActionRecordText.Writes writes =
                    ActionRecordText.parse(text, key).meta().writes();
            return writes != null && writes.once();
        } catch (RuntimeException unreadable) {
            return false; // a torn record is replaced by a whole one
        }
    }

    private void reachable() throws IOException {
        if (!Files.exists(root)) throw new NoSuchFileException(root.toString(), null, "no such cache directory");
        if (!Files.isDirectory(root)) throw new NotDirectoryException(root.toString());
    }
}
