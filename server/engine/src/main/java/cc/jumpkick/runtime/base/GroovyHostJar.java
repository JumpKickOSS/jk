// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The Groovy runners jk forks: {@code JkGroovyHost}, which compiles a script into a jar and runs a
 * build-logic script with its bindings, and {@code JkGroovyMain}, which runs a {@code jk tool run}
 * script's compiled classes. One jar, compiled against the Groovy the script runs on.
 */
public final class GroovyHostJar {

    /** The build-logic runner's main class, also the compiler a tool script's plan forks. */
    public static final String HOST = "JkGroovyHost";

    /** The tool-script launcher's main class. */
    public static final String MAIN = "JkGroovyMain";

    public static final ResourceToolJar JAR =
            new ResourceToolJar("groovy-host", "jk-groovy-host.jar", List.of("JkGroovyHost.java", "JkGroovyMain.java"));

    private GroovyHostJar() {}

    /** Hash of the runner at the pinned Groovy version; part of every compiled build-logic script's key. */
    static String hash() throws IOException {
        return JAR.hash(BuildLogicGroovyHost.GROOVY_VER);
    }

    /** The runner jar compiled against the pinned {@code groovyJar}. */
    static Path ensure(Path groovyJar) throws IOException {
        return ensure(groovyJar, BuildLogicGroovyHost.GROOVY_VER);
    }

    /** The runner jar compiled against {@code groovyJar}, Groovy {@code version}. */
    public static Path ensure(Path groovyJar, String version) throws IOException {
        return JAR.ensure(List.of(groovyJar), version);
    }
}
