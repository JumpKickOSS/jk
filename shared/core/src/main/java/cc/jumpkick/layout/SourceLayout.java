// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.model.Layout;
import cc.jumpkick.model.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves Mill-like vs Maven source roots. {@link Layout#AUTO} (the default) probes the
 * tree; {@code simple} / {@code traditional} in {@code jk.toml} override when the tree is
 * ambiguous.
 */
public final class SourceLayout {

    /** Dirs probed under {@code src/main/} and {@code src/test/} for the traditional (Maven) tree. */
    public static final List<String> TRADITIONAL_MAIN_DIRS = List.of("java", "kotlin", "scala", "groovy", "resources");

    private SourceLayout() {}

    /**
     * True for the Mill-like tree when {@code layout} is AUTO and no Maven marker dirs exist.
     * Prefer {@link #isSimpleLayout(Project, Path)} when a parsed project is available.
     */
    public static boolean isSimpleLayout(Path projectDir) {
        return !looksTraditional(projectDir);
    }

    /**
     * True for simple Mill-like layout ({@code src/} + {@code test/src/}): always for SIMPLE, never
     * for TRADITIONAL, AUTO when no traditional Maven markers are present.
     */
    public static boolean isSimpleLayout(Project project, Path projectDir) {
        return switch (project.layout()) {
            case SIMPLE -> true;
            case TRADITIONAL -> false;
            case AUTO -> !looksTraditional(projectDir);
        };
    }

    /**
     * True when a Maven marker directory exists: {@code src/main/<lang|resources>} or {@code
     * src/test/<lang|resources>}. The test roots matter for test-only modules (a workspace member
     * holding just {@code src/test/java}): classified simple, its tests would compile as MAIN
     * sources — the simple main root is {@code src/}, walked recursively — without the test
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
