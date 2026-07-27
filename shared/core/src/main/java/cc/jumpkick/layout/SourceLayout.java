// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Resolves {@code project.layout} to simple vs traditional source roots. */
public final class SourceLayout {

    private SourceLayout() {}

    /**
     * True for simple Mill-like layout ({@code src/} + {@code test/src/}): always for SIMPLE, never
     * TRADITIONAL, AUTO when Maven source roots are empty/absent.
     */
    public static boolean isSimpleLayout(JkBuild.Project project, Path projectDir) {
        return switch (project.layout()) {
            case SIMPLE -> true;
            case TRADITIONAL -> false;
            case AUTO ->
                !anySourceUnder(projectDir.resolve("src/main/kotlin"), ".kt", ".java", ".groovy")
                        && !anySourceUnder(projectDir.resolve("src/main/java"), ".kt", ".java", ".groovy")
                        && !anySourceUnder(projectDir.resolve("src/main/groovy"), ".kt", ".java", ".groovy");
        };
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
