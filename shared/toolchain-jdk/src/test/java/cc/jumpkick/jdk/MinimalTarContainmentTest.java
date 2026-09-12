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
 * The tar extractors' entry-name check is lexical, so a symlink entry pointing outside the
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
                .hasMessageContaining("outside the destination");
        assertThat(outside.resolve("evil.service")).doesNotExist();
    }

    @Test
    void a_link_created_through_an_in_tree_link_is_judged_by_where_it_lands(@TempDir Path tmp) throws Exception {
        // d/l -> .. resolves to the destination itself, which is fine. A second link written
        // through it, d/l/l2 -> .., physically lands at <dest>/l2 and points one level above.
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        Files.createDirectories(dest.resolve("d"));
        MinimalTar.createSymlinkInside(dest, dest.resolve("d/l"), "..");
        assertThat(dest.resolve("d/l").toRealPath()).isEqualTo(dest.toRealPath());

        assertThatThrownBy(() -> MinimalTar.createSymlinkInside(dest, dest.resolve("d/l/l2"), ".."))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes destination");
        assertThat(dest.resolve("l2")).doesNotExist();
    }

    @Test
    void a_directory_entry_through_a_planted_link_chain_is_refused_before_anything_is_created(@TempDir Path tmp)
            throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        // The chain as an attacker would leave it: l2 sits in the destination and points above it.
        Files.createDirectories(dest.resolve("d"));
        Files.createSymbolicLink(dest.resolve("d/l"), Path.of(".."));
        Files.createSymbolicLink(dest.resolve("l2"), Path.of(".."));

        assertThatThrownBy(() -> MinimalTar.createDirectoryInside(dest, dest.resolve("d/l/l2/pwn/deeper")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the destination");
        assertThat(tmp.resolve("pwn")).doesNotExist();

        assertThatThrownBy(() -> MinimalTar.requireParentInside(dest, dest.resolve("d/l/l2/pwn/evil")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the destination");
        assertThat(tmp.resolve("pwn")).doesNotExist();
    }

    @Test
    void a_link_target_that_routes_through_a_planted_link_is_followed(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.createSymbolicLink(dest.resolve("hop"), outside);
        // Lexically hop/../etc stays in the tree; on disk hop is <outside>, so it does not.
        assertThatThrownBy(() -> MinimalTar.createSymlinkInside(dest, dest.resolve("lib"), "hop/sub"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes destination");
        assertThat(dest.resolve("lib")).doesNotExist();
    }

    @Test
    void nested_directories_are_created_and_reported_by_real_path(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        Path real = MinimalTar.createDirectoryInside(dest, dest.resolve("lib/security/policy"));
        assertThat(real).isEqualTo(dest.toRealPath().resolve("lib/security/policy"));
        assertThat(dest.resolve("lib/security/policy")).isDirectory();
    }

    @Test
    void ordinary_nested_file_parent_is_accepted(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("jdk"));
        MinimalTar.requireParentInside(dest, dest.resolve("bin/java"));
        assertThat(dest.resolve("bin")).isDirectory();
    }
}
