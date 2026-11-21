// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Settled;
import cc.jumpkick.host.StatIdentity;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An image layer kept across builds under {@code image-layers/<key>}, so an image whose layer has
 * not changed reuses its compressed blob instead of tarring and compressing it again.
 *
 * <p>Jib keys its own layer cache on each source file's path and modification time, never on its
 * bytes, so a cache shared between builds could serve a rebuilt file's old bytes. A kept layer is
 * keyed on more than that, one of two ways:
 *
 * <ul>
 *   <li>{@link #byContent} keys release jars on every entry's image path, mode, time and owner and
 *       the sha256 of its bytes. The jars are copied under {@code files/} and the layer is built
 *       from those copies, whose paths and times never change, so Jib's selector for them is
 *       stable too.
 *   <li>{@link #byIdentity} keys a tree too large to hash on every build (a packager's app tree,
 *       {@code node_modules}) on the same entry fields and each source's path and {@link
 *       StatIdentity}, which a rewrite in place moves. Nothing is copied: the layer is built from
 *       the tree itself. A tree with a file written inside {@link Settled#WINDOW_MS}, or on a file
 *       system that cannot report a change time and file id, is not kept.
 * </ul>
 *
 * <p>After a build the layer's blob and selector are taken from the build's own Jib cache into
 * {@code jib/}, and the next build of the same key starts with them in its cache. Snapshot, sibling
 * and application layers stay in the build's own throwaway cache.
 */
final class KeptLayer {

    /** The layer as the image builds it. */
    private final FileEntriesLayer layer;

    /** {@code image-layers/<key>}. */
    private final Path home;

    /** Each source's identity when the key was taken, by source; empty for a layer keyed by bytes. */
    private final Map<Path, StatIdentity> identities;

    private KeptLayer(FileEntriesLayer layer, Path home, Map<Path, StatIdentity> identities) {
        this.layer = layer;
        this.home = home;
        this.identities = identities;
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
     * {@code layer}, a layer of release jars, kept under {@code cacheRoot} by their bytes, the jars
     * copied there on first use. Returns null when a jar changed while it was copied: the build then
     * uses {@code layer} as given.
     */
    static @Nullable KeptLayer byContent(Path cacheRoot, FileEntriesLayer layer) throws IOException {
        List<FileEntry> entries = sorted(layer);
        StringBuilder key = new StringBuilder(jibIdentity()).append('\n');
        List<String> shas = new ArrayList<>();
        for (FileEntry e : entries) {
            String sha = Hashing.sha256Hex(e.getSourceFile());
            shas.add(sha);
            key.append(entryKey(e)).append('\t').append(sha).append('\n');
        }
        Path root = CacheTree.IMAGE_LAYERS.under(cacheRoot);
        Path home = root.resolve(Hashing.sha256Hex(key.toString()));
        if (!Files.isDirectory(home.resolve("files")) && !stage(root, home, entries, shas)) return null;
        stampUse(home);

        FileEntriesLayer.Builder kept = FileEntriesLayer.builder().setName(layer.getName());
        for (FileEntry e : entries) {
            kept.addEntry(new FileEntry(
                    copyOf(home, e.getExtractionPath()),
                    e.getExtractionPath(),
                    e.getPermissions(),
                    e.getModificationTime(),
                    e.getOwnership()));
        }
        return new KeptLayer(kept.build(), home, Map.of());
    }

    /**
     * {@code layer} kept under {@code cacheRoot} by its sources' paths and identities, built from
     * the sources themselves. Returns null when a source's identity cannot vouch for its bytes: the
     * build then uses {@code layer} in its throwaway cache.
     */
    static @Nullable KeptLayer byIdentity(Path cacheRoot, FileEntriesLayer layer) throws IOException {
        StringBuilder key = new StringBuilder(jibIdentity()).append("\nidentity\n");
        Map<Path, StatIdentity> identities = new HashMap<>();
        for (FileEntry e : sorted(layer)) {
            Path source = e.getSourceFile().toAbsolutePath();
            StatIdentity id = StatIdentity.of(source);
            if (id.ctimeNanos() == 0 || id.fileKey().isEmpty() || !Settled.is(id)) return null;
            identities.put(source, id);
            key.append(entryKey(e))
                    .append('\t')
                    .append(source)
                    .append('\t')
                    .append(id.encode())
                    .append('\n');
        }
        Path home = CacheTree.IMAGE_LAYERS.under(cacheRoot).resolve(Hashing.sha256Hex(key.toString()));
        Files.createDirectories(home);
        stampUse(home);
        return new KeptLayer(layer, home, Map.copyOf(identities));
    }

    /** {@code layer}'s entries in image-path order. */
    private static List<FileEntry> sorted(FileEntriesLayer layer) {
        List<FileEntry> entries = new ArrayList<>(layer.getEntries());
        entries.sort(Comparator.comparing(e -> e.getExtractionPath().toString()));
        return entries;
    }

    /** What the layer's tar records of {@code e}: its image path, mode, time and owner. */
    private static String entryKey(FileEntry e) {
        return e.getExtractionPath() + "\t" + e.getPermissions().toOctalString() + "\t" + e.getModificationTime() + "\t"
                + e.getOwnership();
    }

    /** Stamp {@code home} used now: retention ranks these trees by their newest file. */
    private static void stampUse(Path home) throws IOException {
        Path used = home.resolve(".used");
        try {
            Files.createFile(used);
        } catch (FileAlreadyExistsException present) {
            // Stamped below either way.
        }
        Files.setLastModifiedTime(used, FileTime.fromMillis(Clock.SYSTEM.millis()));
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
     * it, unless this key already keeps one or a source keyed by identity changed meanwhile.
     */
    void keep(Path jibCache) throws IOException {
        Path kept = home.resolve("jib");
        if (Files.isDirectory(kept) || moved()) return;
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

    /** True when a source keyed by identity changed during the build: its blob may hold either bytes. */
    private boolean moved() throws IOException {
        for (Map.Entry<Path, StatIdentity> e : identities.entrySet()) {
            if (!Files.isRegularFile(e.getKey()) || !StatIdentity.of(e.getKey()).equals(e.getValue())) return true;
        }
        return false;
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
