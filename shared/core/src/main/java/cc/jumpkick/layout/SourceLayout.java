// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Resolves {@code layout} to simple vs traditional source roots. */
public final class SourceLayout {

    private SourceLayout() {}

    /**
     * True for simple Mill-like layout ({@code src/} + {@code test/src/}): always for SIMPLE, never
     * TRADITIONAL, AUTO when no traditional Maven markers are present.
     */
    public static boolean isSimpleLayout(JkBuild.Project project, Path projectDir) {
        return switch (project.layout()) {
            case SIMPLE -> true;
            case TRADITIONAL -> false;
            case AUTO -> !looksTraditional(projectDir);
        };
    }

    /**
     * True when the tree has Maven-style roots. Without this, AUTO mis-classifies resources-only
     * modules ({@code src/main/resources} + {@code src/test/java}) as SIMPLE: main compile walks all
     * of {@code src/} (including tests) without the test classpath, and resources are expected at
     * top-level {@code resources/} instead of {@code src/main/resources}.
     */
    static boolean looksTraditional(Path projectDir) {
        if (anySourceUnder(projectDir.resolve("src/main/kotlin"), ".kt", ".java", ".groovy")
                || anySourceUnder(projectDir.resolve("src/main/java"), ".kt", ".java", ".groovy")
                || anySourceUnder(projectDir.resolve("src/main/groovy"), ".kt", ".java", ".groovy")) {
            return true;
        }
        return Files.isDirectory(projectDir.resolve("src/main/resources"))
                || Files.isDirectory(projectDir.resolve("src/test/java"))
                || Files.isDirectory(projectDir.resolve("src/test/kotlin"))
                || Files.isDirectory(projectDir.resolve("src/test/groovy"))
                || Files.isDirectory(projectDir.resolve("src/test/resources"));
    }

    private static boolean anySourceUnder(Path root, String... extensions) {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.anyMatch(p -> {
                if (!Files.isRegularFile(p)) return false;
                String name = p.getFileName().toString();
                for (String ext : extensions) if (name.endsWith(ext)) return true;
                return false;
            });
        } catch (IOException e) {
            return false;
        }
    }
}
