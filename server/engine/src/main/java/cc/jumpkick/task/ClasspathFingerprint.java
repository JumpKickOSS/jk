// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Content (not path/mtime) fingerprint for cache keys: CAS path encodes the hash; local files use
 * raw content SHA ({@code file:…}); directories use a tree of the same. Packagers emit
 * byte-reproducible jars ({@code DeterministicZip}), so raw jar bytes are stable across no-op
 * rebuilds and match CAS digests seeded by {@link FileHashMemo#rememberContent} after clean→restore.
 * Missing entries become a distinct {@code missing:} token.
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
     * forward-slash relative paths; values are content SHA-256 hex. Stamp / {@code .jk-*} scratch
     * paths are ignored like a live tree walk. {@link #entry(Path)} routes a live directory through
     * here too, so the two can never disagree about what a tree's token is.
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
                hashInto(root, digests, false);
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
            hashInto(classesDir, digests, true);
        }
        return entryFromCompileAndResources(digests, resourceRoots);
    }

    /**
     * Memoized content digest of every regular file under {@code root}, keyed by forward-slash
     * relative path. {@code skipScratch} additionally drops {@code .jk-*} plugin scratch, which is
     * output content in a classes tree but never in a resource root.
     */
    private static void hashInto(Path root, Map<String, String> digests, boolean skipScratch) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                if (BuildStamps.isStampFile(f.getFileName().toString())) return FileVisitResult.CONTINUE;
                Path rel = root.relativize(f);
                if (skipScratch && ActionCache.hasJkScratchSegment(rel)) return FileVisitResult.CONTINUE;
                digests.put(
                        rel.toString().replace('\\', '/'),
                        FileHashMemo.contentHash(f.toAbsolutePath().normalize(), attrs));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Content identity of a single entry (CAS blob, jar, classes dir, or missing). */
    public static String entry(Path p) throws IOException {
        Path abs = p.toAbsolutePath().normalize();
        BasicFileAttributes attrs;
        try {
            // One attribute read answers directory / regular / missing, where three predicates
            // would each re-resolve the path.
            attrs = Files.readAttributes(abs, BasicFileAttributes.class);
        } catch (IOException absent) {
            return "missing:" + abs;
        }
        if (attrs.isDirectory()) {
            // Through entryFromOutputDigests so a live tree and the same tree read back out of an
            // action record cannot drift: one place decides what a directory token is.
            Map<String, String> digests = new TreeMap<>();
            hashInto(abs, digests, true);
            return entryFromOutputDigests(digests);
        }
        // Raw content for jars and non-jars alike: deterministic packaging makes raw stable, and
        // rememberContent can seed it for free after an action-cache restore. The memo is what
        // stops every build from re-hashing each non-CAS jar (repos/ deps, sibling module jars,
        // worker fat jars).
        if (attrs.isRegularFile()) return "file:" + FileHashMemo.contentHash(abs, attrs);
        return "missing:" + abs;
    }
}
