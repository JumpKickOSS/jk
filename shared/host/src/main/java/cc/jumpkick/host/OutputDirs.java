// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where a build tool keeps its output, judged by position and never by name alone. A {@code target/}
 * directory beside a {@code jk.toml} or a {@code pom.xml} is a module's output. A {@code build/}
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

    /** The directory a module builds into, beside its manifest. */
    public static final String TARGET = "target";

    /** The scripts Gradle reads from a project directory; {@code build/} sits beside one of them. */
    static final List<String> GRADLE_SCRIPTS =
            List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /**
     * True when {@code dir} is a project's output directory: a {@code target} beside a module manifest
     * or a {@code pom.xml}, or a {@code build} beside a module manifest or a Gradle script, with no
     * manifest of its own. Either name anywhere else is not output and is not skipped.
     */
    public static boolean isBuildOutputDir(Path dir) {
        Path name = dir.getFileName();
        if (name == null) return false;
        Path parent = dir.getParent();
        if (parent == null) return false;
        if (name.toString().equals(TARGET)) {
            return !Files.exists(dir.resolve(ManifestNames.MANIFEST))
                    && (Files.exists(parent.resolve(ManifestNames.MANIFEST))
                            || Files.exists(parent.resolve("pom.xml")));
        }
        if (!name.toString().equals("build")) return false;
        if (Files.exists(dir.resolve(ManifestNames.MANIFEST))) return false;
        if (Files.exists(parent.resolve(ManifestNames.MANIFEST))) return true;
        for (String script : GRADLE_SCRIPTS) {
            if (Files.exists(parent.resolve(script))) return true;
        }
        return false;
    }
}
