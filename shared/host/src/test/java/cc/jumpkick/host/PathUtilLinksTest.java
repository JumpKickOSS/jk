// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A link in an input root is read through, or named; never left out without a word. */
class PathUtilLinksTest {

    private static boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException notPermitted) {
            return false;
        }
    }

    @Test
    void a_link_to_a_file_is_visited_at_its_path_with_its_targets_attributes(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src"));
        Path outside = Files.writeString(tmp.resolve("Shared.java"), "class Shared {}");
        assumeTrue(link(root.resolve("Shared.java"), outside), "symbolic links can be made here");

        List<Path> seen = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        PathUtil.forEachRegularFile(root, (file, attrs) -> {
            seen.add(file);
            sizes.add(attrs.size());
        });

        assertThat(seen).containsExactly(root.resolve("Shared.java"));
        assertThat(sizes).containsExactly(Files.size(outside));
    }

    @Test
    void a_link_to_a_directory_is_reported_and_not_entered(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src"));
        Path outside = Files.createDirectories(tmp.resolve("shared"));
        Files.writeString(outside.resolve("A.java"), "class A {}");
        assumeTrue(link(root.resolve("shared"), outside), "symbolic links can be made here");

        List<Path> files = new ArrayList<>();
        List<Path> links = new ArrayList<>();
        PathUtil.forEachRegularFile(root, new PathUtil.FileVisit() {
            @Override
            public void accept(Path file, BasicFileAttributes attrs) {
                files.add(file);
            }

            @Override
            public void directoryLink(Path link) {
                links.add(link);
            }
        });

        assertThat(files).isEmpty();
        assertThat(links).containsExactly(root.resolve("shared"));
    }
}
