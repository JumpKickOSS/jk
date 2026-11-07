// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Directories whose content a read-only reader knows before the build writes it: each projected
 * root maps its tree-relative paths to content shas, the files a build step will lay down there
 * (an action record it restores) in place of whatever is on disk now, or nothing (a root the step
 * removes). Bound around a forecast with {@link #with}, the hashing and listing primitives that
 * key on such a root ({@link FileHashMemo#contentHash(Path)}, {@link ClasspathFingerprint#entry},
 * the generated-source and output-root readers) answer from it, so a key priced against a wiped or
 * stale root is the key the build computes once the root is back.
 *
 * <p>Bound as a {@link ScopedValue}, never a static: two forecasts can run at once in one engine,
 * and a live build, which writes the very roots, must never see one.
 */
public final class ProjectedFiles {

    private static final ScopedValue<ProjectedFiles> CURRENT = ScopedValue.newInstance();

    /** Projected root (absolute, normalized) to its files, forward-slash relative path to sha. */
    private final Map<Path, Map<String, String>> roots = new ConcurrentHashMap<>();

    /** Where the bytes of a projected sha are read from: the store the shas name. */
    private final Function<String, Path> blobs;

    /** A projection whose shas name blobs {@code blobs} locates, such as an action cache's CAS. */
    public ProjectedFiles(Function<String, Path> blobs) {
        this.blobs = blobs;
    }

    /** Lay {@code files} (relative path to content sha; empty for a root that goes) at {@code root}. */
    public void project(Path root, Map<String, String> files) {
        roots.put(root.toAbsolutePath().normalize(), Map.copyOf(files));
    }

    /** Run {@code body} with {@code projection} answering for its roots. */
    public static <T> T with(ProjectedFiles projection, Callable<T> body) throws Exception {
        return ScopedValue.where(CURRENT, projection).<T, Exception>call(body::call);
    }

    /**
     * The files projected at {@code dir}, relative path to sha, sorted by path; null when no bound
     * projection names {@code dir} as a root, so the caller reads the disk.
     */
    public static @Nullable Map<String, String> at(Path dir) {
        if (!CURRENT.isBound()) return null;
        Map<String, String> files = CURRENT.get().roots.get(dir.toAbsolutePath().normalize());
        return files == null ? null : new TreeMap<>(files);
    }

    /** The projected sha of {@code file}, under a projected root; null when no root holds it. */
    public static @Nullable String contentHash(Path file) {
        if (!CURRENT.isBound()) return null;
        Path abs = file.toAbsolutePath().normalize();
        for (Map.Entry<Path, Map<String, String>> root : CURRENT.get().roots.entrySet()) {
            if (!abs.startsWith(root.getKey())) continue;
            String rel = root.getKey().relativize(abs).toString().replace('\\', '/');
            return root.getValue().get(rel);
        }
        return null;
    }

    /**
     * The file holding the bytes the build lays at {@code file}: its projected sha's blob when a
     * root holds it, so a reader that parses the file reads what the build will; null when no root
     * holds it or its blob is gone, so the caller reads the disk.
     */
    public static @Nullable Path blob(Path file) {
        String sha = contentHash(file);
        if (sha == null) return null;
        Path blob = CURRENT.get().blobs.apply(sha);
        return Files.isRegularFile(blob) ? blob : null;
    }

    /** Whether {@code file} is projected under a root, or, under none, is a regular file on disk. */
    public static boolean isRegularFile(Path file) {
        if (contentHash(file) != null) return true;
        return !underRoot(file) && Files.isRegularFile(file);
    }

    /** Whether {@code dir} is a projected root holding a file, or, if not projected, a directory on disk. */
    public static boolean isDirectory(Path dir) {
        Map<String, String> files = at(dir);
        return files != null ? !files.isEmpty() : Files.isDirectory(dir);
    }

    /** The projected files under root {@code dir} ending with {@code suffix}, as paths, sorted; null when not projected. */
    public static @Nullable List<Path> files(Path dir, String suffix) {
        Map<String, String> files = at(dir);
        if (files == null) return null;
        List<Path> out = new ArrayList<>();
        for (String rel : files.keySet()) {
            if (rel.endsWith(suffix)) out.add(dir.resolve(rel));
        }
        out.sort(null);
        return out;
    }

    private static boolean underRoot(Path file) {
        if (!CURRENT.isBound()) return false;
        Path abs = file.toAbsolutePath().normalize();
        for (Path root : CURRENT.get().roots.keySet()) {
            if (abs.startsWith(root)) return true;
        }
        return false;
    }
}
