// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where another build tool keeps its output, judged by position and never by name alone. Gradle
 * writes {@code build/} beside the script that declares the project, so a directory named {@code
 * build} anywhere else — the package {@code cc.jumpkick.plugin.build} under {@code src/}, a module
 * a team happened to call {@code build} — is source and is walked. Every walker that prunes Gradle
 * output asks here, so the answer cannot drift between the guard text lane, {@code jk format} and
 * the closure tests.
 */
public final class OutputDirs {
    private OutputDirs() {}

    /** The scripts Gradle reads from a project directory; {@code build/} sits beside one of them. */
    static final List<String> GRADLE_SCRIPTS =
            List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /**
     * True when {@code dir} is a Gradle project's {@code build/} directory: named {@code build},
     * with a Gradle build or settings script in its parent. A {@code build} directory beside no
     * script is not Gradle's and is not skipped.
     */
    public static boolean isGradleBuildDir(Path dir) {
        Path name = dir.getFileName();
        if (name == null || !name.toString().equals("build")) return false;
        Path parent = dir.getParent();
        if (parent == null) return false;
        for (String script : GRADLE_SCRIPTS) {
            if (Files.exists(parent.resolve(script))) return true;
        }
        return false;
    }
}
