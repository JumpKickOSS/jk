// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * d8 / R8 judge program and {@code --lib} inputs by file extension. Store-materialized runtime
 * jars are CAS blobs with no extension ({@code store/sha256/xx/yy/<hex>}), which d8/R8 reject as
 * "Unsupported source file type" — so every extensionless input gets a {@code .jar}-suffixed
 * hard-link alias under the step's scratch before it reaches the tool (JK-1449).
 */
final class JarInputs {

    private JarInputs() {}

    /** A {@code .jar}-suffixed alias of {@code source} under the step's scratch. */
    static Path jarNamed(TaskExec exec, Path source, String name) throws IOException {
        Path alias = Files.createDirectories(exec.scratch().resolve("tools")).resolve(name);
        return linkOrCopy(source, alias);
    }

    /** Alias every extensionless jar in {@code jars} as {@code .jar}; pass suffixed ones through. */
    static List<Path> jarSuffixed(TaskExec exec, List<Path> jars) throws IOException {
        List<Path> out = new ArrayList<>(jars.size());
        Path dir = null;
        int i = 0;
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            if (name.endsWith(".jar") || name.endsWith(".zip")) {
                out.add(jar);
            } else {
                if (dir == null) dir = Files.createDirectories(exec.scratch().resolve("rt-jars"));
                out.add(linkOrCopy(jar, dir.resolve("rt-" + i + "-" + name + ".jar")));
            }
            i++;
        }
        return out;
    }

    private static Path linkOrCopy(Path source, Path alias) throws IOException {
        Files.deleteIfExists(alias);
        try {
            Files.createLink(alias, source);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(source, alias);
        }
        return alias;
    }
}
