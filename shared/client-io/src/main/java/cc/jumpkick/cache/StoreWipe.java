// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.JumpKickPins;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole-store delete behind {@code jk storage nuke} and {@code jk self nuke}, which keeps the
 * running jk's own shelf: {@code repos/jk-local/cc/jumpkick/<artifact>/<version>/} for this
 * version. Everything else under the store can be fetched again. Those jars cannot: a jk built
 * from a checkout ({@code -SNAPSHOT}, or a version that was never released) launches its workers
 * and built-in plugins from the jars {@code jk install} shelved there, and no repository has them.
 * A nuke that took them left a jk that could not build anything that configures a built-in
 * plugin's table, including JumpKick itself, so it could not run the install that restores them.
 */
public final class StoreWipe {

    private StoreWipe() {}

    /** The first-party group's directory on the shelf, relative to the store root. */
    static Path firstPartyShelf(Path storeRoot) {
        Path group = storeRoot.resolve("repos").resolve(RepositorySpec.JK_LOCAL);
        for (String segment : JumpKickPins.GROUP.split("\\.")) group = group.resolve(segment);
        return group;
    }

    /**
     * {@code repos/jk-local/cc/jumpkick/*}{@code /<version>} in the host's separators: how the confirm
     * names what a wipe keeps. Text, not a {@link Path}: Windows rejects {@code *} in a path.
     */
    public static String shelfPattern(Path storeRoot, String version) {
        String sep = storeRoot.getFileSystem().getSeparator();
        return firstPartyShelf(storeRoot) + sep + "*" + sep + version;
    }

    /**
     * The version directories a wipe keeps: one per first-party artifact on the shelf that has
     * {@code version}. Real directories only; a link is deleted as itself, never kept or followed.
     */
    public static List<Path> kept(Path storeRoot, String version) throws IOException {
        Path group = firstPartyShelf(storeRoot);
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(group, LinkOption.NOFOLLOW_LINKS)) return out;
        PathUtil.forEachChild(group, (artifact, attrs) -> {
            if (!attrs.isDirectory()) return true;
            Path versionDir = artifact.resolve(version);
            if (Files.isDirectory(versionDir, LinkOption.NOFOLLOW_LINKS)) out.add(versionDir);
            return true;
        });
        out.sort(null);
        return out;
    }

    /** {@link #kept(Path, String)} for the running jk's version. */
    public static List<Path> kept(Path storeRoot) throws IOException {
        return kept(storeRoot, JkVersion.VERSION);
    }

    /**
     * The trees a wipe deletes: every entry under {@code storeRoot} that is neither a kept
     * directory nor an ancestor of one, as the highest such paths. With nothing kept that is the
     * store root itself, so a store holding no shelf goes as a directory, as it always did.
     */
    public static List<Path> doomed(Path storeRoot, List<Path> kept) throws IOException {
        if (!Files.exists(storeRoot, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (kept.isEmpty()) return List.of(storeRoot);
        List<Path> out = new ArrayList<>();
        collect(storeRoot, kept, out);
        out.sort(null);
        return out;
    }

    private static void collect(Path dir, List<Path> kept, List<Path> out) throws IOException {
        PathUtil.forEachChild(dir, (child, attrs) -> {
            if (kept.contains(child)) return true;
            boolean leadsToKept = attrs.isDirectory() && kept.stream().anyMatch(k -> k.startsWith(child));
            if (leadsToKept) collect(child, kept, out);
            else out.add(child);
            return true;
        });
    }

    /** What {@link #wipe} would remove: files and unique bytes, hard links counted once. */
    public static DiskUsage.Stats measure(Path storeRoot) throws IOException {
        return measure(storeRoot, JkVersion.VERSION);
    }

    static DiskUsage.Stats measure(Path storeRoot, String version) throws IOException {
        DiskUsage.Stats[] parts = DiskUsage.exclusive(doomed(storeRoot, kept(storeRoot, version)));
        return new DiskUsage.Stats(DiskUsage.totalFiles(parts), DiskUsage.totalBytes(parts));
    }

    /**
     * Delete the store except this version's first-party shelf. The memos beside the kept jars
     * stay with them, so the resolvers still find them; the CAS blobs and indexes go, and the
     * writers rebuild them on demand.
     */
    public static void wipe(Path storeRoot) throws IOException {
        wipe(storeRoot, JkVersion.VERSION);
    }

    static void wipe(Path storeRoot, String version) throws IOException {
        List<Path> doomed = doomed(storeRoot, kept(storeRoot, version));
        if (doomed.isEmpty()) return;
        PathUtil.deleteTrees(doomed, new PathUtil.Removed());
    }
}
