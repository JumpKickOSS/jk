// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Content (not path/mtime) fingerprint for cache keys: CAS path encodes the hash; local jars/dirs
 * use logical content (sorted entry/file digests) so non-reproducible re-jars do not bust keys.
 * Missing entries become a distinct {@code missing:} token.
 */
public final class ClasspathFingerprint {

    private ClasspathFingerprint() {}

    /** Order-independent content fingerprint of a list of classpath entries. */
    public static String of(List<Path> entries) throws IOException {
        List<String> parts = new ArrayList<>(entries.size());
        for (Path p : entries) parts.add(entry(p));
        parts.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", parts));
    }

    /** Content identity of a single entry (CAS blob, jar, classes dir, or missing). */
    public static String entry(Path p) throws IOException {
        String abs = p.toAbsolutePath().normalize().toString();
        if (isCasPath(abs)) return "cas:" + abs; // path encodes content hash
        if (Files.isDirectory(p)) return "dir:" + hashTree(p);
        if (Files.isRegularFile(p)) {
            // Stat fast-path: an unchanged (size+mtime, settled) file keeps its memoized
            // fingerprint — this is what stops every build from re-inflating and re-hashing
            // each non-CAS jar (repos/ deps, sibling module jars, worker fat jars). The memo
            // is validated per-read and fails open to the content hash below.
            long size = Files.size(p);
            long mtime = Files.getLastModifiedTime(p).toMillis();
            String memoized = FileHashMemo.lookup(p, size, mtime);
            if (memoized != null && (memoized.startsWith("jar:") || memoized.startsWith("file:"))) {
                return memoized;
            }
            String token;
            if (isArchive(abs)) {
                String logical = hashArchive(p);
                token = logical != null
                        ? "jar:" + logical // logical content, not raw bytes
                        : "file:" + Hashing.sha256Hex(p);
            } else {
                token = "file:" + Hashing.sha256Hex(p);
            }
            FileHashMemo.store(p, size, mtime, token);
            return token;
        }
        return "missing:" + abs;
    }

    private static boolean isArchive(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    /**
     * Logical content hash of a zip/jar: sorted {@code (entry name + entry SHA)}, ignoring entry
     * order, timestamps, and compression — the packaging that jk does not produce reproducibly.
     * Returns {@code null} if {@code jar} isn't a valid archive, so the caller falls back to a
     * raw-byte hash.
     */
    private static String hashArchive(Path jar) throws IOException {
        List<String> entries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || isBuildMetadata(baseName(e.getName()))) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    entries.add(e.getName() + ":" + Hashing.sha256Hex(in.readAllBytes()));
                }
            }
        } catch (ZipException ze) {
            return null; // not a valid zip — caller hashes raw bytes
        }
        entries.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", entries));
    }

    /** Stable hash of a directory tree: each regular file's relpath + content SHA. */
    private static String hashTree(Path dir) throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(f)) continue;
                if (isBuildMetadata(f.getFileName().toString())) continue;
                files.add(dir.relativize(f).toString().replace('\\', '/') + ":" + Hashing.sha256Hex(f));
            }
        }
        files.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", files));
    }

    /**
     * jk's freshness/skip stamps ({@code .jstamp}, {@code .kstamp}, {@code .test-stamp}) — build-host
     * metadata that lives inside the classes tree but is not code, and whose content changes every
     * build. They must be excluded from a content fingerprint of a directory (the packagers already
     * drop them from jars, so {@link #hashArchive} never sees them).
     *
     * <p>Note {@code [build.embed-sha]} outputs ({@code META-INF/jk-<worker>-sha256.txt}) are
     * deliberately <em>not</em> excluded: now that the packagers build byte-reproducible jars, those
     * embedded SHAs are stable across no-op rebuilds, and a genuine change to a worker jar
     * <em>should</em> ripple through the embedded SHA into every module that pins it.
     */
    private static boolean isBuildMetadata(String name) {
        return name.equals(FreshnessStamp.JAVA_STAMP) || name.equals(FreshnessStamp.KOTLIN_STAMP);
    }

    private static String baseName(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    /** A path under {@code .../sha256/AA/BB/<rest>} is a CAS blob (path = content). */
    private static boolean isCasPath(String path) {
        return path.contains("/sha256/") || path.contains("\\sha256\\");
    }
}
