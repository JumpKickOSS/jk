// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1464: the tar extractors' entry-name check is lexical, so a symlink entry pointing outside the
 * destination — followed by a file entry written through it — escaped the tree. These cover the
 * containment helpers both extractors now share.
 */
@DisabledOnOs(OS.WINDOWS)
class MinimalTarContainmentTest {

    @Test
    void in_tree_relative_link_is_allowed(@TempDir Path tmp) throws Exception {
        // What real JDK archives contain: jre/lib -> ../lib.
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        Files.createDirectories(dest.resolve("lib"));
        Path link = dest.resolve("jre/lib");
        MinimalTar.createSymlinkInside(dest, link, "../lib");
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(Path.of("../lib"));
    }

    @Test
    void absolute_link_target_is_refused(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        assertThatThrownBy(() -> MinimalTar.createSymlinkInside(dest, dest.resolve("lib"), "/etc"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void traversing_link_target_is_refused(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        assertThatThrownBy(
                        () -> MinimalTar.createSymlinkInside(dest, dest.resolve("lib"), "../../../../home/victim/.ssh"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes destination");
    }

    @Test
    void write_through_a_planted_link_is_refused(@TempDir Path tmp) throws Exception {
        // The two-entry attack: entry 1 plants the link, entry 2 writes through it. Even if the
        // link were created out-of-band, the file entry's parent must still resolve inside.
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.createSymbolicLink(dest.resolve("lib"), outside);
        assertThatThrownBy(() -> MinimalTar.requireParentInside(dest, dest.resolve("lib/evil.service")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("writes through a link");
        assertThat(outside.resolve("evil.service")).doesNotExist();
    }

    @Test
    void ordinary_nested_file_parent_is_accepted(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        MinimalTar.requireParentInside(dest, dest.resolve("bin/java"));
        assertThat(dest.resolve("bin")).isDirectory();
    }
}
