// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.compile.WorkerClasspath;
import cc.jumpkick.repo.PomRuntimeClasspath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Classpath used to fork a thin plugin worker.
 *
 * <p>Preferred: sibling Maven POM + jars in {@code repos/local} (and the other store repos), the
 * same graph {@code jk install} / {@code mvn install} publishes. Fallback: {@link WorkerClasspath}
 * ({@code store/lib}, {@code .classpath} sidecar, nearby plugin-sdk) for Gradle bootstrap and
 * in-tree {@code target/} jars that are not in a repo yet.
 */
public final class WorkerLaunchClasspath {

    private static final String PLUGIN_MAIN = "cc/jumpkick/plugin/process/PluginMain.class";

    private WorkerLaunchClasspath() {}

    public static List<Path> paths(Path workerJar) {
        List<Path> fromPom = PomRuntimeClasspath.resolveOrNull(workerJar);
        if (fromPom != null && (fromPom.size() > 1 || classpathHas(fromPom, PLUGIN_MAIN))) {
            return fromPom;
        }
        return WorkerClasspath.paths(workerJar);
    }

    public static String resolve(Path workerJar) {
        String sep = System.getProperty("path.separator", ":");
        return paths(workerJar).stream().map(Path::toString).collect(Collectors.joining(sep));
    }

    private static boolean classpathHas(List<Path> entries, String classFile) {
        for (Path p : entries) {
            if (jarContains(p, classFile)) return true;
        }
        return false;
    }

    private static boolean jarContains(Path jar, String entryName) {
        if (jar == null || !Files.isRegularFile(jar)) return false;
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry(entryName) != null;
        } catch (IOException e) {
            return false;
        }
    }
}
