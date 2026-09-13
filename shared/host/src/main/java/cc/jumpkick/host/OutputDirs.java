// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where a build tool keeps its output, judged by position and never by name alone. A {@code build/}
 * directory sits beside the file that declares the project it belongs to: a {@code jk.toml} module
 * manifest (the tree's own modules, and whatever another tool left there before jk), or a Gradle
 * build or settings script (a dual-build tree). A directory named {@code build} anywhere else — the
 * package {@code cc.jumpkick.plugin.build} under {@code src/}, a module a team happened to call
 * {@code build}, which holds a manifest of its own — is source and is walked. Every walker that
 * prunes build output asks here, so the answer cannot drift between the guard text lane, {@code jk
 * format} and the closure tests.
 */
public final class OutputDirs {
    private OutputDirs() {}

    /** The scripts Gradle reads from a project directory; {@code build/} sits beside one of them. */
    static final List<String> GRADLE_SCRIPTS =
            List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /**
     * True when {@code dir} is a project's {@code build/} output directory: named {@code build}, with
     * a module manifest or a Gradle script in its parent, and no manifest of its own. A {@code build}
     * directory beside neither is not output and is not skipped.
     */
    public static boolean isBuildOutputDir(Path dir) {
        Path name = dir.getFileName();
        if (name == null || !name.toString().equals("build")) return false;
        Path parent = dir.getParent();
        if (parent == null) return false;
        if (Files.exists(dir.resolve(ManifestNames.MANIFEST))) return false;
        if (Files.exists(parent.resolve(ManifestNames.MANIFEST))) return true;
        for (String script : GRADLE_SCRIPTS) {
            if (Files.exists(parent.resolve(script))) return true;
        }
        return false;
    }
}
