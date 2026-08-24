// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Content (not path/mtime) fingerprint for cache keys: CAS path encodes the hash; local files use
 * raw content SHA ({@code file:…}); directories use a tree of the same. Packagers emit
 * byte-reproducible jars ({@code DeterministicZip}), so raw jar bytes are stable across no-op
 * rebuilds and match CAS digests seeded by {@link FileHashMemo#rememberContent} after clean→restore
 * — avoiding a multi-second {@code jar:logical} zip walk on every TestStamp. Missing entries become
 * a distinct {@code missing:} token.
 *
 * <p>Every walk here drops {@link BuildStamps#isStampFile stamp files}: build-host metadata that
 * lives inside the classes tree, is not code, and whose content changes every build. {@code
 * [build.embed-sha]} outputs ({@code META-INF/jk-<worker>-sha256.txt}) are deliberately <em>not</em>
 * dropped — byte-reproducible jars keep those embedded SHAs stable across no-op rebuilds, and a
 * genuine change to a worker jar <em>should</em> ripple into every module that pins it.
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

    /**
     * Directory fingerprint matching {@link #entry(Path)} for a classes tree that is not on disk —
     * typically the outputs map from a compile action record after {@code jk clean}. Keys are
     * forward-slash relative paths; values are content SHA-256 hex (same as {@link #hashTree}).
     * Stamp / {@code .jk-*} scratch paths are ignored like a live tree walk.
     */
    public static String entryFromOutputDigests(Map<String, String> relPathToSha256) {
        if (relPathToSha256 == null || relPathToSha256.isEmpty()) {
            return "dir:" + Hashing.sha256Hex("");
        }
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : relPathToSha256.entrySet()) {
            String rel = e.getKey().replace('\\', '/');
            if (rel.isEmpty()) continue;
            if (BuildStamps.isStampFile(rel)) continue;
            if (ActionCache.hasJkScratchSegment(Path.of(rel))) continue;
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            files.add(rel + ":" + e.getValue());
        }
        files.sort(Comparator.naturalOrder());
        return "dir:" + Hashing.sha256Hex(String.join("\n", files));
    }

    /**
     * Merge compile outputs with resource-root files (as {@code copy-resources} would place them
     * under classes/) into the same {@code dir:…} token {@link #entry(Path)} produces for a live
     * tree. Resource paths overwrite compile paths on collision (copy order: compile then
     * resources).
     */
    public static String entryFromCompileAndResources(Map<String, String> compileOutputs, List<Path> resourceRoots)
            throws IOException {
        Map<String, String> digests = new TreeMap<>();
        if (compileOutputs != null) {
            for (Map.Entry<String, String> e : compileOutputs.entrySet()) {
                String rel = e.getKey().replace('\\', '/');
                if (rel.isEmpty()) continue;
                if (BuildStamps.isStampFile(rel)) continue;
                if (ActionCache.hasJkScratchSegment(Path.of(rel))) continue;
                if (e.getValue() == null || e.getValue().isBlank()) continue;
                digests.put(rel, e.getValue());
            }
        }
        if (resourceRoots != null) {
            for (Path root : resourceRoots) {
                if (root == null || !Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path f : (Iterable<Path>) walk::iterator) {
                        if (!Files.isRegularFile(f)) continue;
                        String rel = root.relativize(f).toString().replace('\\', '/');
                        if (BuildStamps.isStampFile(f.getFileName().toString())) continue;
                        digests.put(rel, Hashing.sha256Hex(f));
                    }
                }
            }
        }
        return entryFromOutputDigests(digests);
    }

    /**
     * Projected directory token for a live classes tree after {@code copy-resources} would merge
     * {@code resourceRoots} over it. Starts from on-disk class/non-resource files, then overlays
     * source resource roots — same content the live package step hashes once the copy has run.
     */
    public static String entryProjectedAfterResourceCopy(Path classesDir, List<Path> resourceRoots) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        if (classesDir != null && Files.isDirectory(classesDir)) {
            try (Stream<Path> walk = Files.walk(classesDir)) {
                for (Path f : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(f)) continue;
                    if (BuildStamps.isStampFile(f.getFileName().toString())) continue;
                    Path rel = classesDir.relativize(f);
                    if (ActionCache.hasJkScratchSegment(rel)) continue;
                    digests.put(rel.toString().replace('\\', '/'), Hashing.sha256Hex(f));
                }
            }
        }
        return entryFromCompileAndResources(digests, resourceRoots);
    }

    /** Content identity of a single entry (CAS blob, jar, classes dir, or missing). */
    public static String entry(Path p) throws IOException {
        String abs = p.toAbsolutePath().normalize().toString();
        if (Files.isDirectory(p)) return "dir:" + hashTree(p);
        if (Files.isRegularFile(p)) {
            // Stat fast-path: an unchanged (size+mtime, settled) file keeps its memoized
            // fingerprint — this is what stops every build from re-inflating and re-hashing
            // each non-CAS jar (repos/ deps, sibling module jars, worker fat jars). The memo
            // is validated per-read and fails open to the content hash below.
            long size = Files.size(p);
            long mtime = Files.getLastModifiedTime(p).toMillis();
            String memoized = FileHashMemo.lookup(p, size, mtime);
            // file: = raw content (incl. CAS restore seeds). jar: = legacy logical zip token;
            // still honor it so an older hash-memo entry does not force a re-walk mid-build.
            if (memoized != null && (memoized.startsWith("file:") || memoized.startsWith("jar:"))) {
                return memoized;
            }
            // Raw content for jars and non-jars alike: deterministic packaging makes raw stable,
            // and rememberContent can seed it for free after action-cache restore.
            String token = "file:" + FileHashMemo.contentHash(p);
            // Settle-gated disk memo only (not force-store): content can change at the same
            // size+mtime on coarse clocks. CAS restore seeds via FileHashMemo.rememberContent.
            FileHashMemo.store(p, size, mtime, token);
            return token;
        }
        return "missing:" + abs;
    }

    /** Stable hash of a directory tree: each regular file's relpath + content SHA. */
    private static String hashTree(Path dir) throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(f)) continue;
                if (BuildStamps.isStampFile(f.getFileName().toString())) continue;
                // `.jk-*` plugin scratch (bootstrap m2/staging) is not output content and
                // re-hashing it on every no-op build is pure waste.
                if (ActionCache.hasJkScratchSegment(dir.relativize(f))) continue;
                // FileHashMemo (thread + disk): after ActionCache.restore seeds CAS digests,
                // TestStamp must not re-SHA every .class (jk-engine was ~10s on a SKIPPED run-tests).
                files.add(dir.relativize(f).toString().replace('\\', '/') + ":" + FileHashMemo.contentHash(f));
            }
        }
        files.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", files));
    }
}
