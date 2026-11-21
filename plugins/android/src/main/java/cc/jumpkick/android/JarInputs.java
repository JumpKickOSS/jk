// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.host.Linking;
import cc.jumpkick.host.ToolJars;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * d8 / R8 judge program and {@code --lib} inputs by file extension. Dependency jars are
 * Maven-layout {@code *.jar} files; any extensionless input still gets a {@code .jar}-suffixed
 * alias under the step's private scratch so the tool accepts it.
 */
final class JarInputs {

    private JarInputs() {}

    /** A {@code .jar}-suffixed alias of {@code source} under the step's scratch. */
    static Path jarNamed(TaskExec exec, Path source, String name) throws IOException {
        Path alias = exec.privateDir("tools").resolve(name);
        return linkOrCopy(source, alias);
    }

    /** Alias every extensionless jar in {@code jars} as {@code .jar}; pass suffixed ones through. */
    static List<Path> jarSuffixed(TaskExec exec, List<Path> jars) throws IOException {
        return ToolJars.jarSuffixed(exec.privateDir("rt-jars"), jars);
    }

    private static Path linkOrCopy(Path source, Path alias) throws IOException {
        Linking.linkOrCopy(source, alias);
        return alias;
    }
}
