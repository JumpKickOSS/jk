// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves Mill-like vs Maven source roots from the tree. Presence of {@code
 * src/main/{java,kotlin,scala,groovy,resources}} (in that order) is traditional; otherwise simple.
 * Directory existence is enough — no file walk, and no {@code jk.toml} override.
 */
public final class SourceLayout {

    /** Dirs probed under {@code src/main/} for the traditional (Maven) tree. */
    public static final List<String> TRADITIONAL_MAIN_DIRS = List.of("java", "kotlin", "scala", "groovy", "resources");

    private SourceLayout() {}

    /**
     * True for the Mill-like tree ({@code src/} + {@code test/src/}): no {@code
     * src/main/{java,kotlin,scala,groovy,resources}} directory.
     */
    public static boolean isSimpleLayout(Path projectDir) {
        return !looksTraditional(projectDir);
    }

    /**
     * True when {@code src/main/java}, {@code src/main/kotlin}, {@code src/main/scala}, {@code
     * src/main/groovy}, or {@code src/main/resources} exists as a directory.
     */
    public static boolean looksTraditional(Path projectDir) {
        if (projectDir == null) return false;
        Path main = projectDir.resolve("src").resolve("main");
        for (String name : TRADITIONAL_MAIN_DIRS) {
            if (Files.isDirectory(main.resolve(name))) return true;
        }
        return false;
    }
}
