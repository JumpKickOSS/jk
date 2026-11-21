// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.run.JkThreads;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks a downloaded JDK or build-tool archive. Every entry is judged by where it really lands
 * ({@link MinimalTar#entryPath}), and one that would land outside the destination is refused before
 * anything under it is written.
 */
public final class ArchiveExtractor {

    private ArchiveExtractor() {}

    /**
     * Extract a {@code zip}, {@code tar.gz} or {@code tar} archive into {@code destDir}; an entry that
     * would land outside it is refused before anything under it is written.
     */
    public static void extract(Path archive, Path destDir, String archiveType) throws IOException {
        Files.createDirectories(destDir);
        switch (archiveType) {
            case "zip" -> unzip(archive, destDir, name -> true);
            case "tar.gz", "tgz", "targz" -> extractTar(archive, destDir, true);
            case "tar" -> extractTar(archive, destDir, false);
            default -> throw new IOException("unsupported archive type: " + archiveType);
        }
    }

    /**
     * Extract only the entries of a {@code zip} whose names {@code keep} accepts (names as the zip
     * spells them, {@code /}-separated and relative to its root). Judged exactly as {@link #extract}
     * judges an entry; an entry that is left out is never read.
     */
    public static void extractZip(Path archive, Path destDir, Predicate<String> keep) throws IOException {
        Files.createDirectories(destDir);
        unzip(archive, destDir, keep);
    }

    private static void extractTar(Path archive, Path destDir, boolean gzipped) throws IOException {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(archive));
                InputStream inflated = gzipped ? new GZIPInputStream(fis) : fis) {
            MinimalTar.stream(inflated, (name, linkName, mode, kind, data, size) -> {
                // Drop macOS AppleDouble sidecars (`._foo`) anywhere in the tree.
                if (isAppleDoubleSidecar(name)) return;
                Path out = MinimalTar.entryPath(destDir, name);
                switch (kind) {
                    case DIRECTORY -> MinimalTar.createDirectoryInside(destDir, out);
                    case SYMLINK -> MinimalTar.createSymlinkInside(destDir, out, linkName);
                    case HARD_LINK -> MinimalTar.createHardLinkInside(destDir, out, linkName);
                    case FILE -> {
                        MinimalTar.requireParentInside(destDir, out);
                        Files.copy(data, out);
                        MinimalTar.applyMode(out, mode);
                    }
                }
            });
        }
    }

    private static boolean isAppleDoubleSidecar(String entryName) {
        int end = entryName.endsWith("/") ? entryName.length() - 1 : entryName.length();
        int slash = entryName.lastIndexOf('/', end - 1);
        String basename = entryName.substring(slash + 1, end);
        return basename.startsWith("._");
    }

    /**
     * Parallel ZIP extraction. ZIP's Central Directory lets us seek to any entry's deflate stream
     * independently, so we fan out across {@link JkThreads#cpu()} workers — meaningful on JDK zips
     * with several thousand entries (Windows JDK builds typically ship as zip).
     *
     * <p>Every entry is judged by where it really lands, the same way the tar path judges its
     * entries: its normalized path through {@link MinimalTar#entryPath}, directories through
     * {@link MinimalTar#createDirectoryInside}, file parents through {@link
     * MinimalTar#requireParentInside}. That happens in the calling thread, entry by entry,
     * before any worker writes a byte — so a refused archive has created nothing beneath the
     * offending path, and the workers never race on creating the same parent.
     *
     * <p>Tar.gz can't be parallelized this way: gunzip is a single sequential stream and the tar
     * metadata is interleaved with the file data.
     */
    private static void unzip(Path archive, Path destDir, Predicate<String> keep) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            entries.removeIf(e -> !keep.test(e.getName()));
            for (ZipEntry e : entries) {
                Path out = MinimalTar.entryPath(destDir, e.getName());
                if (e.isDirectory()) MinimalTar.createDirectoryInside(destDir, out);
                else MinimalTar.requireParentInside(destDir, out);
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>(entries.size());
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) continue;
                Path out = MinimalTar.entryPath(destDir, entry.getName());
                futures.add(CompletableFuture.runAsync(
                        () -> {
                            try (InputStream in = zip.getInputStream(entry)) {
                                Files.copy(in, out);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        },
                        JkThreads.cpu()));
            }
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof UncheckedIOException uio) throw uio.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException("zip extraction failed: " + cause.getMessage(), cause);
            }
        }
    }

    /**
     * The root of what an archive unpacked into {@code stagingDir}: its single top-level directory
     * when it has one ({@code jdk-21.0.5+11/}, {@code apache-maven-3.9.9/}), else the staging dir.
     */
    public static Path root(Path stagingDir) throws IOException {
        List<Path> children = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        PathUtil.forEachChild(stagingDir, (child, attrs) -> {
            children.add(child);
            if (attrs.isDirectory()) dirs.add(child);
            return true;
        });
        Path root = children.size() == 1 && dirs.size() == 1 ? dirs.getFirst() : stagingDir;
        // Extraction judged each link when it was written, against the staging directory; a later
        // entry can replace a link an earlier one routed through, and a lifted root is one level
        // lower than what was judged. The final tree is judged again as a whole, whatever its shape.
        MinimalTar.requireSymlinksInside(root);
        return root;
    }
}
