// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.repo.PomRuntimeClasspath;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Classpath used to fork a thin plugin worker: the worker jar plus the Maven runtime closure from
 * its POM ({@code repos/local} / {@code jumpkick} / {@code central}).
 */
public final class WorkerLaunchClasspath {

    private WorkerLaunchClasspath() {}

    public static List<Path> paths(Path workerJar) {
        return PomRuntimeClasspath.resolve(workerJar);
    }

    public static String resolve(Path workerJar) {
        String sep = System.getProperty("path.separator", ":");
        return paths(workerJar).stream().map(Path::toString).collect(Collectors.joining(sep));
    }
}
