// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolves the JVM classpath for a thin plugin/worker jar.
 *
 * <p>Preferred layout after install: {@code <worker>.jar} plus optional sidecar {@code
 * <worker>.jar.classpath} — one absolute jar path per line ({@code #} comments and blanks
 * ignored). When the sidecar is absent, the worker jar alone is used (workers that vendor
 * plugin-sdk into the jar, or pure JDK tools).
 *
 * <p>Ugly multi-path {@code -cp} is intentional for JK-1347; JK-1348 will prefer short paths
 * under {@code store/lib/<name>/}.
 */
public final class WorkerClasspath {

    private WorkerClasspath() {}

    /** Sidecar path convention: {@code foo.jar} → {@code foo.jar.classpath}. */
    public static Path sidecarPath(Path workerJar) {
        return Path.of(workerJar.toString() + ".classpath");
    }

    /**
     * Classpath entries: worker jar first, then sidecar entries that still exist. Missing sidecar
     * paths are skipped (stale install) rather than failing the launch line.
     */
    public static List<Path> paths(Path workerJar) {
        List<Path> entries = new ArrayList<>();
        entries.add(workerJar.toAbsolutePath().normalize());
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
                // Fall back to jar-only; launcher will fail clearly if classes are missing.
            }
        }
        return entries;
    }

    /**
     * Classpath string for {@code -cp}: worker jar first, then sidecar entries that still exist.
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
}
