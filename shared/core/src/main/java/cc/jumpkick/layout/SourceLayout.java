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
     * src/main/*} or {@code src/test/*} marker directory.
     */
    public static boolean isSimpleLayout(Path projectDir) {
        return !looksTraditional(projectDir);
    }

    /**
     * True when a Maven marker directory exists: {@code src/main/<lang|resources>} or {@code
     * src/test/<lang|resources>}. The test roots matter for test-only modules (a workspace
     * member holding just {@code src/test/java}): classified simple, its tests would compile as
     * MAIN sources — the simple main root is {@code src/}, walked recursively — without the test
     * classpath, and stop being discovered as tests.
     */
    public static boolean looksTraditional(Path projectDir) {
        if (projectDir == null) return false;
        Path src = projectDir.resolve("src");
        for (String parent : List.of("main", "test")) {
            Path root = src.resolve(parent);
            for (String name : TRADITIONAL_MAIN_DIRS) {
                if (Files.isDirectory(root.resolve(name))) return true;
            }
        }
        return false;
    }
}
