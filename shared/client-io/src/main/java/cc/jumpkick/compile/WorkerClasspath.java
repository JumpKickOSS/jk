// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves the JVM classpath for a thin plugin/worker jar.
 *
 * <p><strong>Preferred (JK-1348):</strong> {@code $JK_LIB_DIR/&lt;id&gt;/} (default {@code
 * store/lib/&lt;id&gt;/}, shared with installed tools) populated at install with hardlinked jars +
 * ordered {@code .classpath}. Compact paths in {@code ps}.
 *
 * <p><strong>Fallback (JK-1347):</strong> {@code <worker>.jar} plus optional sidecar {@code
 * <worker>.jar.classpath} — one absolute jar path per line ({@code #} comments and blanks
 * ignored).
 *
 * <p>If the worker jar does not contain {@code PluginMain} (thin pure-jk package without a
 * vendored codec) and neither lib-dir nor sidecar supplies it, we also try to locate {@code
 * jk-plugin-sdk} nearby (workspace {@code target/…} or {@code store/repos/local/…}) so forks
 * still start.
 */
public final class WorkerClasspath {

    private static final String PLUGIN_MAIN = "cc/jumpkick/plugin/process/PluginMain.class";

    private WorkerClasspath() {}

    /** Sidecar path convention: {@code foo.jar} → {@code foo.jar.classpath}. */
    public static Path sidecarPath(Path workerJar) {
        return Path.of(workerJar.toString() + ".classpath");
    }

    /**
     * Classpath entries: prefer {@link WorkerLib} when installed; else worker jar first, then
     * sidecar entries that still exist, then a best-effort {@code plugin-sdk} jar when {@link
     * #PLUGIN_MAIN} is not inside the worker.
     */
    public static List<Path> paths(Path workerJar) {
        Path worker = workerJar.toAbsolutePath().normalize();
        // JK-1348: short store/lib/<id>/ paths when materialize has run for this worker.
        List<Path> libPaths = WorkerLib.pathsIfPresent(worker);
        if (libPaths != null && !libPaths.isEmpty()) {
            return libPaths;
        }
        List<Path> entries = new ArrayList<>();
        entries.add(worker);
        Path side = sidecarPath(workerJar);
        if (Files.isRegularFile(side)) {
            try {
                for (String line : Files.readAllLines(side, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    Path p = Path.of(t).toAbsolutePath().normalize();
                    if (Files.isRegularFile(p) && !entries.contains(p)) entries.add(p);
                }
            } catch (IOException e) {
                // Fall back; may still find plugin-sdk below.
            }
        }
        if (!jarContains(worker, PLUGIN_MAIN)) {
            Path sdk = findPluginSdk(worker);
            if (sdk != null && !entries.contains(sdk)) entries.add(sdk);
        }
        return entries;
    }

    /**
     * Classpath string for {@code -cp}: {@link WorkerLib} paths when present, else worker +
     * sidecar / plugin-sdk entries.
     */
    public static String resolve(Path workerJar) {
        String sep = System.getProperty("path.separator", ":");
        return paths(workerJar).stream().map(Path::toString).collect(Collectors.joining(sep));
    }

    /** Write a classpath sidecar next to {@code workerJar} (overwrites). */
    public static void writeSidecar(Path workerJar, List<Path> dependencyJars) throws IOException {
        Path side = sidecarPath(workerJar);
        StringBuilder sb = new StringBuilder();
        sb.append("# jk worker classpath — generated; do not edit by hand\n");
        for (Path p : dependencyJars) {
            if (p == null) continue;
            Path abs = p.toAbsolutePath().normalize();
            if (Files.isRegularFile(abs)) {
                sb.append(abs).append('\n');
            }
        }
        Files.createDirectories(side.getParent());
        Files.writeString(side, sb.toString(), StandardCharsets.UTF_8);
    }

    static boolean jarContains(Path jar, String entryName) {
        if (!Files.isRegularFile(jar)) return false;
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry(entryName) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Best-effort locate of {@code jk-plugin-sdk} / {@code plugin-sdk} jar near a worker path or
     * under the shared store.
     */
    static Path findPluginSdk(Path workerJar) {
        Path abs = workerJar.toAbsolutePath().normalize();
        // Workspace pure-jk: …/target/plugins/<name>/jk-….jar → …/target/shared/plugin-sdk/lib/
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            Path target = dir.resolve("target");
            if (Files.isDirectory(target)) {
                Path hit = firstJar(target.resolve("shared/plugin-sdk/lib"), "jk-plugin-sdk");
                if (hit == null) hit = firstJar(target.resolve("shared/plugin-sdk/lib"), "plugin-sdk");
                if (hit != null) return hit;
            }
            // Gradle: …/plugins/kotlin-compiler/build/libs/X.jar → …/shared/plugin-sdk/build/libs/
            Path sdkGradle = dir.resolve("shared/plugin-sdk/build/libs");
            Path hit = firstJar(sdkGradle, "plugin-sdk");
            if (hit == null) hit = firstJar(sdkGradle, "jk-plugin-sdk");
            if (hit != null) return hit;
            // Stop at filesystem root
            if (dir.getParent() == null) break;
            // Don't walk forever — monorepos are shallow
            if (dir.getNameCount() < 2) break;
        }
        // Installed: ~/.jk/store/repos/local/cc/jumpkick/jk-plugin-sdk/<ver>/*.jar
        Path storeLocal = JkDirs.store().resolve("repos/local/cc/jumpkick");
        for (String artifact : List.of("jk-plugin-sdk", "plugin-sdk")) {
            Path base = storeLocal.resolve(artifact);
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> vers = Files.list(base)) {
                List<Path> versionDirs =
                        vers.filter(Files::isDirectory).sorted().toList();
                // Prefer highest version string last
                for (int i = versionDirs.size() - 1; i >= 0; i--) {
                    Path hit = firstJar(versionDirs.get(i), artifact);
                    if (hit != null) return hit;
                }
            } catch (IOException ignored) {
                /* try next */
            }
        }
        return null;
    }

    private static Path firstJar(Path dir, String namePrefix) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, namePrefix + "*.jar")) {
            Path best = null;
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                // Prefer non-sources / non-javadoc
                String n = p.getFileName().toString();
                if (n.contains("-sources") || n.contains("-javadoc")) continue;
                if (best == null || n.compareTo(best.getFileName().toString()) > 0) best = p;
            }
            return best;
        } catch (IOException e) {
            return null;
        }
    }
}
