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
 * <p><strong>Preferred:</strong> {@code $JK_LIB_DIR/&lt;id&gt;/} (default {@code
 * store/lib/&lt;id&gt;/}, shared with installed tools) populated at install with hardlinked jars +
 * ordered {@code .classpath}. Compact paths in {@code ps}. Used only when the lib dir was
 * materialized from the exact jar being launched (inode match — ); otherwise the sidecar
 * fallback below wins so upgrades and {@code -Djk.*.plugin.jar} overrides are honored.
 *
 * <p><strong>Fallback:</strong> {@code <worker>.jar} plus optional sidecar {@code
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
     *
     * <p>When the sidecar lists lib-dir basenames that no longer exist (Gradle {@code installLocal}
     * and Gradle {@code installLocal} rematerialize {@code store/lib/&lt;id&gt;/} under
     * different filenames), recover the live deps from that lib dir's order file — without taking
     * the lib's worker jar, so a {@code -Djk.*.plugin.jar} override still launches the override
     * bytes.
     */
    public static List<Path> paths(Path workerJar) {
        Path worker = workerJar.toAbsolutePath().normalize();
        // short store/lib/<id>/ paths when materialize has run for this worker.
        List<Path> libPaths = WorkerLib.pathsIfPresent(worker);
        if (libPaths != null && !libPaths.isEmpty()) {
            return libPaths;
        }
        List<Path> entries = new ArrayList<>();
        entries.add(worker);
        boolean missingSidecarEntry = false;
        Path side = sidecarPath(workerJar);
        if (Files.isRegularFile(side)) {
            try {
                for (String line : Files.readAllLines(side, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    Path p = Path.of(t).toAbsolutePath().normalize();
                    if (!Files.isRegularFile(p)) {
                        missingSidecarEntry = true;
                        continue;
                    }
                    if (!entries.contains(p)) entries.add(p);
                }
            } catch (IOException e) {
                // Fall back; may still find plugin-sdk below.
            }
        }
        if (missingSidecarEntry) {
            recoverLibDeps(worker, entries);
        }
        if (!jarContains(worker, PLUGIN_MAIN)) {
            Path sdk = findPluginSdk(worker);
            if (sdk != null && !entries.contains(sdk)) entries.add(sdk);
        }
        // plugin-sdk is a thin jar: Jsonl lives in jk-jsonl. Gradle workers vendor both into
        // the worker; pure-jk thin jars do not. Isolated JK_HOME also hides store/lib recover.
        // Without this, kotlinc / test-runner die with NoClassDefFoundError: Jsonl.
        if (!classpathHas(entries, "cc/jumpkick/jsonl/Jsonl.class")) {
            Path jsonl = findJsonl(worker);
            if (jsonl != null && !entries.contains(jsonl)) entries.add(jsonl);
        }
        return entries;
    }

    /**
     * Append deps from {@code store/lib/&lt;id&gt;/} when a sidecar entry vanished after a
     * rematerialize. Skips the lib's own worker jar so overrides keep their bytes.
     */
    private static void recoverLibDeps(Path worker, List<Path> entries) {
        List<Path> lib = WorkerLib.pathsIfPresent(WorkerLib.idFromWorkerJar(worker));
        if (lib == null) return;
        for (Path p : lib) {
            if (p == null || !Files.isRegularFile(p)) continue;
            try {
                if (Files.isSameFile(worker, p)) continue;
            } catch (IOException ignored) {
                // vanished mid-check
            }
            // Lib worker jar (different inode / bytes) must not replace the override on -cp.
            String name = p.getFileName() != null ? p.getFileName().toString() : "";
            String workerName =
                    worker.getFileName() != null ? worker.getFileName().toString() : "";
            if (!name.isEmpty() && name.equals(workerName)) continue;
            if (!entries.contains(p)) entries.add(p);
        }
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
        return findFirstPartyJar(
                workerJar,
                List.of("jk-plugin-sdk", "plugin-sdk"),
                "shared/plugin-sdk/lib",
                "shared/plugin-sdk/build/libs");
    }

    /**
     * {@code jk-jsonl} / {@code jsonl} next to a thin worker. plugin-sdk imports Jsonl but does
     * not vendor it.
     */
    static Path findJsonl(Path workerJar) {
        return findFirstPartyJar(
                workerJar, List.of("jk-jsonl", "jsonl"), "shared/jsonl/lib", "shared/jsonl/build/libs");
    }

    private static Path findFirstPartyJar(Path workerJar, List<String> artifacts, String targetRel, String gradleRel) {
        Path abs = workerJar.toAbsolutePath().normalize();
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            Path target = dir.resolve("target");
            if (Files.isDirectory(target)) {
                Path hit = firstJarNamed(target.resolve(targetRel), artifacts);
                if (hit != null) return hit;
            }
            Path hit = firstJarNamed(dir.resolve(gradleRel), artifacts);
            if (hit != null) return hit;
            // After searching this level: @TempDir trees (often <module>/build/tmp/junit-*)
            // must not climb into the surrounding monorepo and steal workspace jars.
            if (isScratchDir(dir)) break;
            if (dir.getParent() == null) break;
            if (dir.getNameCount() < 2) break;
        }
        for (String repoName : List.of("local", "jumpkick")) {
            Path storeRepo = JkDirs.store().resolve("repos").resolve(repoName).resolve("cc/jumpkick");
            for (String artifact : artifacts) {
                Path base = storeRepo.resolve(artifact);
                if (!Files.isDirectory(base)) continue;
                try (Stream<Path> vers = Files.list(base)) {
                    List<Path> versionDirs =
                            vers.filter(Files::isDirectory).sorted().toList();
                    for (int i = versionDirs.size() - 1; i >= 0; i--) {
                        Path hit = firstJar(versionDirs.get(i), artifact);
                        if (hit != null) return hit;
                    }
                } catch (IOException ignored) {
                    // try next artifact / repo
                }
            }
        }
        return null;
    }

    private static boolean isScratchDir(Path dir) {
        if (dir == null) return false;
        String n = dir.getFileName() != null ? dir.getFileName().toString() : "";
        return n.equals("tmp")
                || n.equals("temp")
                || n.startsWith("junit-")
                || n.startsWith("jk-junit-")
                || n.startsWith("jkt-")
                || n.startsWith("jkd-")
                || n.startsWith("jk-cli-");
    }

    private static boolean classpathHas(List<Path> entries, String classFile) {
        for (Path p : entries) {
            if (jarContains(p, classFile)) return true;
        }
        return false;
    }

    private static Path firstJarNamed(Path dir, List<String> prefixes) {
        for (String prefix : prefixes) {
            Path hit = firstJar(dir, prefix);
            if (hit != null) return hit;
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
