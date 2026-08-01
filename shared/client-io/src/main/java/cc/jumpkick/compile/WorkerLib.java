// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.cache.Linking;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stable short classpaths for thin plugin/tool workers (JK-1348).
 *
 * <p>On install, each worker's main jar and runtime deps are hard-linked into {@link
 * JkDirs#lib()}{@code /&lt;id&gt;/} (default {@code ~/.jk/store/lib/&lt;id&gt;/} — same tree as
 * installed tools). Launch prefers those paths so {@code ps} shows compact {@code -cp
 * …/store/lib/&lt;id&gt;/…} entries instead of long CAS/repos absolute paths.
 *
 * <p><strong>GC contract:</strong> these hardlinks keep the CAS inode alive after a normal
 * repos/CAS unlink sweep. Uninstall ({@link #remove}) drops the lib dir so a later GC can reclaim
 * unreferenced blobs.
 */
public final class WorkerLib {

    /** Ordered basename list written under each lib dir (one jar name per line). */
    public static final String ORDER_FILE = ".classpath";

    private WorkerLib() {}

    /** {@link JkDirs#lib()} — shared tool + plugin jar root. */
    public static Path root() {
        return JkDirs.lib();
    }

    /** Lib directory for one worker/tool id (sanitized). */
    public static Path dir(String id) {
        return root().resolve(sanitizeId(id));
    }

    /**
     * Derive a stable id from a worker jar path. Prefers the Maven layout artifactId segment
     * ({@code …/jk-kotlin-compiler/0.10.1/jk-kotlin-compiler-0.10.1.jar} → {@code
     * jk-kotlin-compiler}); falls back to stripping a trailing {@code -&lt;version&gt;} from the
     * filename.
     */
    public static String idFromWorkerJar(Path workerJar) {
        if (workerJar == null) return "worker";
        Path abs = workerJar.toAbsolutePath().normalize();
        Path parent = abs.getParent(); // version dir or build/libs
        if (parent != null) {
            Path grand = parent.getParent(); // artifactId in m2 layout
            String ver = parent.getFileName() != null ? parent.getFileName().toString() : "";
            String art = grand != null && grand.getFileName() != null
                    ? grand.getFileName().toString()
                    : "";
            // …/artifactId/version/file.jar
            if (!art.isBlank() && looksLikeVersion(ver) && art.startsWith("jk-")) {
                return sanitizeId(art);
            }
            if (!art.isBlank() && looksLikeVersion(ver) && !art.equals("local") && !art.equals("jumpkick")) {
                return sanitizeId(art);
            }
        }
        String file = abs.getFileName() != null ? abs.getFileName().toString() : "worker.jar";
        return sanitizeId(stripJarVersion(file));
    }

    /**
     * Materialize {@code workerJar} + {@code depJars} into {@code store/lib/&lt;id&gt;/} as
     * hardlinks (copy fallback). Writes {@link #ORDER_FILE} for launch order (worker first).
     * Replaces any previous contents of the lib dir.
     *
     * @return the lib directory
     */
    public static Path materialize(String id, Path workerJar, List<Path> depJars) throws IOException {
        String safe = sanitizeId(id);
        Path d = dir(safe);
        clearDir(d);
        Files.createDirectories(d);
        List<String> order = new ArrayList<>();
        linkInto(d, workerJar, order);
        if (depJars != null) {
            for (Path dep : depJars) {
                if (dep == null) continue;
                Path abs = dep.toAbsolutePath().normalize();
                if (!Files.isRegularFile(abs)) continue;
                if (workerJar != null && abs.equals(workerJar.toAbsolutePath().normalize())) continue;
                linkInto(d, abs, order);
            }
        }
        writeOrder(d, order);
        return d;
    }

    /** Convenience: id from worker jar path. */
    public static Path materialize(Path workerJar, List<Path> depJars) throws IOException {
        return materialize(idFromWorkerJar(workerJar), workerJar, depJars);
    }

    /**
     * If {@code store/lib/&lt;id&gt;/} has a valid order file (or jars), return absolute paths in
     * launch order; otherwise {@code null} (caller should fall back to sidecar/CAS paths).
     */
    public static List<Path> pathsIfPresent(String id) {
        Path d = dir(id);
        if (!Files.isDirectory(d)) return null;
        Path orderFile = d.resolve(ORDER_FILE);
        List<Path> out = new ArrayList<>();
        if (Files.isRegularFile(orderFile)) {
            try {
                for (String line : Files.readAllLines(orderFile, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    Path p = d.resolve(t).toAbsolutePath().normalize();
                    if (Files.isRegularFile(p)) out.add(p);
                }
            } catch (IOException e) {
                return null;
            }
        } else {
            // No order file: jars sorted by name (worker name usually sorts with artifact prefix).
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.jar")) {
                List<Path> jars = new ArrayList<>();
                for (Path p : ds) {
                    if (Files.isRegularFile(p)) jars.add(p.toAbsolutePath().normalize());
                }
                jars.sort(Comparator.comparing(p -> p.getFileName().toString()));
                out.addAll(jars);
            } catch (IOException e) {
                return null;
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    /** Resolve lib paths for a worker jar when materialize has been run; else {@code null}. */
    public static List<Path> pathsIfPresent(Path workerJar) {
        return pathsIfPresent(idFromWorkerJar(workerJar));
    }

    /** Remove {@code store/lib/&lt;id&gt;/} so GC may reclaim unreferenced CAS blobs. */
    public static void remove(String id) throws IOException {
        Path d = dir(id);
        if (Files.isDirectory(d)) clearDir(d);
    }

    /** True when a lib dir is populated for this id. */
    public static boolean isPresent(String id) {
        List<Path> p = pathsIfPresent(id);
        return p != null && !p.isEmpty();
    }

    // ── internals ────────────────────────────────────────────────────────────

    static String sanitizeId(String id) {
        if (id == null || id.isBlank()) return "worker";
        String s = id.trim().replace('\\', '/');
        // drop path noise
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        // safe filesystem token
        s = s.replaceAll("[^A-Za-z0-9._-]+", "-");
        while (s.startsWith("-")) s = s.substring(1);
        return s.isEmpty() ? "worker" : s;
    }

    static boolean looksLikeVersion(String s) {
        if (s == null || s.isBlank()) return false;
        // 0.10.1, 1.0.0-SNAPSHOT, 6.1.1
        return s.matches("\\d+(?:[._-][A-Za-z0-9]+)*");
    }

    static String stripJarVersion(String fileName) {
        String n = fileName;
        if (n.toLowerCase(Locale.ROOT).endsWith(".jar")) n = n.substring(0, n.length() - 4);
        // artifact-1.2.3 or artifact-1.2.3-SNAPSHOT
        int dash = n.lastIndexOf('-');
        if (dash > 0 && looksLikeVersion(n.substring(dash + 1))) {
            return n.substring(0, dash);
        }
        // artifact-0.10.1 → already handled; try second dash for 0.10.1-rc1 style already in looksLikeVersion
        return n;
    }

    private static void linkInto(Path dir, Path source, List<String> order) throws IOException {
        if (source == null || !Files.isRegularFile(source)) return;
        String base = source.getFileName().toString();
        String name = uniqueName(dir, base);
        Path target = dir.resolve(name);
        Linking.linkOrCopy(source, target);
        order.add(name);
    }

    private static String uniqueName(Path dir, String base) {
        Path candidate = dir.resolve(base);
        if (!Files.exists(candidate)) return base;
        // collision: prefix short hash of parent path isn't available; use numeric suffix
        String stem = base;
        String ext = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            stem = base.substring(0, dot);
            ext = base.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            String n = stem + "-" + i + ext;
            if (!Files.exists(dir.resolve(n))) return n;
        }
        return stem + "-" + System.nanoTime() + ext;
    }

    private static void writeOrder(Path dir, List<String> order) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# jk worker lib classpath — generated; do not edit by hand\n");
        for (String n : order) sb.append(n).append('\n');
        Files.writeString(dir.resolve(ORDER_FILE), sb.toString(), StandardCharsets.UTF_8);
    }

    private static void clearDir(Path d) throws IOException {
        if (!Files.isDirectory(d)) return;
        try (Stream<Path> walk = Files.walk(d)) {
            List<Path> all = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path p : all) {
                if (p.equals(d)) continue;
                Files.deleteIfExists(p);
            }
        }
        // keep the directory itself for recreate
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path p : ds) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            /* empty */
        }
    }
}
