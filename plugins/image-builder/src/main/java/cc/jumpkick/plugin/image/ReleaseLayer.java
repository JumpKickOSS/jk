// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FileEntry;
import com.google.cloud.tools.jib.cache.Cache;
import com.google.cloud.tools.jib.cache.CacheCorruptedException;
import com.google.cloud.tools.jib.cache.CachedLayer;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.net.URL;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A release-dependency layer kept across builds, so an image whose release jars have not changed
 * reuses their compressed layer instead of tarring and compressing it again.
 *
 * <p>Jib keys its own layer cache on each source file's path and modification time, never on its
 * bytes, so a cache shared between builds could serve a rebuilt jar's old bytes. This one is keyed
 * on what the layer holds: every entry's image path, mode, time and owner, and the sha256 of its
 * bytes. The jars are copied under {@code image-layers/<key>/files/} and the layer is built from
 * those copies, whose paths and times never change, so Jib's selector for them is stable too. After
 * a build the layer's blob and selector are taken from the build's own Jib cache into {@code
 * image-layers/<key>/jib/}, and the next build of the same key starts with them in its cache.
 *
 * <p>Only release jars go through here; snapshot, sibling and application layers stay in the
 * build's own throwaway cache.
 */
final class ReleaseLayer {

    /** The layer as the image builds it: the same entries, read from the kept copies. */
    private final FileEntriesLayer layer;

    /** {@code image-layers/<key>}. */
    private final Path home;

    private ReleaseLayer(FileEntriesLayer layer, Path home) {
        this.layer = layer;
        this.home = home;
    }

    /** The layer to add to the image. */
    FileEntriesLayer layer() {
        return layer;
    }

    /** Where this layer is kept: {@code image-layers/<key>} under the cache root. */
    Path home() {
        return home;
    }

    /**
     * {@code layer} kept under {@code cacheRoot}, its jars copied there on first use. Returns null
     * when a jar changed while it was copied: the build then uses {@code layer} as given.
     */
    static @Nullable ReleaseLayer of(Path cacheRoot, FileEntriesLayer layer) throws IOException {
        List<FileEntry> entries = new ArrayList<>(layer.getEntries());
        entries.sort(Comparator.comparing(e -> e.getExtractionPath().toString()));
        StringBuilder key = new StringBuilder(jibIdentity()).append('\n');
        List<String> shas = new ArrayList<>();
        for (FileEntry e : entries) {
            String sha = Hashing.sha256Hex(e.getSourceFile());
            shas.add(sha);
            key.append(e.getExtractionPath())
                    .append('\t')
                    .append(e.getPermissions().toOctalString())
                    .append('\t')
                    .append(e.getModificationTime())
                    .append('\t')
                    .append(e.getOwnership())
                    .append('\t')
                    .append(sha)
                    .append('\n');
        }
        Path root = CacheTree.IMAGE_LAYERS.under(cacheRoot);
        Path home = root.resolve(Hashing.sha256Hex(key.toString()));
        if (!Files.isDirectory(home.resolve("files")) && !stage(root, home, entries, shas)) return null;
        // Retention ranks these trees by their newest file; the copies carry a pinned time.
        Path used = home.resolve(".used");
        try {
            Files.createFile(used);
        } catch (FileAlreadyExistsException present) {
            // Stamped below either way.
        }
        Files.setLastModifiedTime(used, FileTime.fromMillis(Clock.SYSTEM.millis()));

        FileEntriesLayer.Builder kept = FileEntriesLayer.builder().setName(layer.getName());
        for (FileEntry e : entries) {
            kept.addEntry(new FileEntry(
                    copyOf(home, e.getExtractionPath()),
                    e.getExtractionPath(),
                    e.getPermissions(),
                    e.getModificationTime(),
                    e.getOwnership()));
        }
        return new ReleaseLayer(kept.build(), home);
    }

    /**
     * Copy the jars into a fresh tree beside {@code home} and move it into place; false when a
     * copy's bytes are not the ones hashed, or {@code home} is taken but holds no jars. A build that
     * loses the race to another uses theirs.
     */
    private static boolean stage(Path root, Path home, List<FileEntry> entries, List<String> shas) throws IOException {
        Files.createDirectories(root);
        Path fresh = Files.createTempDirectory(root, home.getFileName() + ".stage-");
        try {
            for (int i = 0; i < entries.size(); i++) {
                FileEntry e = entries.get(i);
                Path copy = copyOf(fresh, e.getExtractionPath());
                Files.createDirectories(copy.getParent());
                Files.copy(e.getSourceFile(), copy);
                if (!Hashing.sha256Hex(copy).equals(shas.get(i))) return false;
                // Jib's selector reads the source's own time; a fixed one keeps it stable.
                Files.setLastModifiedTime(copy, FileTime.from(e.getModificationTime()));
            }
            try {
                Files.move(fresh, home);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException taken) {
                // Another build staged the same bytes first.
            }
            return Files.isDirectory(home.resolve("files"));
        } finally {
            PathUtil.deleteRecursively(fresh);
        }
    }

    private static Path copyOf(Path tree, AbsoluteUnixPath imagePath) {
        return tree.resolve("files").resolve(imagePath.toString().substring(1));
    }

    /**
     * Put the kept blob and selector into {@code jibCache}, the build's own Jib cache, so Jib finds
     * the layer built. Nothing to do before the first build of this key.
     */
    void seed(Path jibCache) throws IOException {
        // Linked where the file system allows: neither side writes through these names.
        PathUtil.copyTree(home.resolve("jib"), jibCache, dir -> false, file -> false, file -> true);
    }

    /**
     * After a build, keep the blob {@code jibCache} holds for this layer and every selector naming
     * it, unless this key already keeps one.
     */
    void keep(Path jibCache) throws IOException {
        Path kept = home.resolve("jib");
        if (Files.isDirectory(kept)) return;
        Optional<CachedLayer> built;
        try {
            built = Cache.withDirectory(jibCache).retrieve(ImmutableList.copyOf(layer.getEntries()));
        } catch (CacheCorruptedException | CacheDirectoryCreationException e) {
            return;
        }
        if (built.isEmpty()) return;
        String digest = built.get().getDigest().getHash();
        Path blobs = jibCache.resolve("layers").resolve(digest);
        Path selectorDir = jibCache.resolve("selectors");
        if (!Files.isDirectory(blobs) || !Files.isDirectory(selectorDir)) return;
        List<Path> selectors = new ArrayList<>();
        PathUtil.forEachRegularFile(selectorDir, (file, attrs) -> {
            if (Files.readString(file).trim().equals(digest)) selectors.add(file);
        });
        if (selectors.isEmpty()) return;
        Path fresh = Files.createTempDirectory(home, "jib.stage-");
        try {
            PathUtil.copyTree(blobs, fresh.resolve("layers").resolve(digest));
            for (Path selector : selectors) {
                Path target = fresh.resolve("selectors")
                        .resolve(selector.getFileName().toString());
                Files.createDirectories(target.getParent());
                Files.copy(selector, target);
            }
            try {
                Files.move(fresh, kept);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException taken) {
                // Another build kept the same layer first.
            }
        } finally {
            PathUtil.deleteRecursively(fresh);
        }
    }

    /**
     * The Jib that writes the layer bytes, by its jar's name: another Jib may tar or compress the
     * same entries differently, and a layer is kept only for the Jib that built it.
     */
    private static String jibIdentity() {
        CodeSource source = Jib.class.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location == null) return "jib";
        String path = location.getPath();
        int slash = path.lastIndexOf('/', path.length() - 2);
        return "jib " + path.substring(slash + 1);
    }
}
