// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A junction reads through to its target, is a reparse point rather than a symlink, and needs no privilege. */
@EnabledOnOs(OS.WINDOWS)
class JunctionsTest {

    @Test
    void a_junction_reads_through_to_its_target(@TempDir Path tmp) throws Exception {
        Path target = Files.createDirectories(tmp.resolve("real").resolve(".ssh"));
        Files.writeString(target.resolve("id_ed25519"), "key");
        Path link = tmp.resolve("run-home-ssh");

        Junctions.create(link, target);

        assertThat(link.resolve("id_ed25519")).hasContent("key");
        BasicFileAttributes attrs = Files.readAttributes(link, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(attrs.isSymbolicLink()).isFalse();
        assertThat(attrs.isDirectory()).isTrue();
        assertThat(attrs.isOther()).as("a reparse point").isTrue();
        assertThat(link.toRealPath()).isEqualTo(target.toRealPath());
    }

    @Test
    void a_junction_to_another_spelling_of_a_relative_target_points_at_its_absolute_form(@TempDir Path tmp)
            throws Exception {
        Path target = Files.createDirectories(tmp.resolve("a").resolve("b"));
        Path link = tmp.resolve("link");

        Junctions.create(link, target.resolve("..").resolve("b"));

        assertThat(link.toRealPath()).isEqualTo(target.toRealPath());
    }

    @Test
    void an_existing_name_or_a_missing_target_is_refused_and_leaves_nothing(@TempDir Path tmp) throws Exception {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Path taken = Files.createDirectories(tmp.resolve("taken"));

        assertThatThrownBy(() -> Junctions.create(taken, target)).isInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> Junctions.create(tmp.resolve("dangling"), tmp.resolve("absent")))
                .hasMessageContaining("not a directory");
        assertThat(tmp.resolve("dangling")).doesNotExist();
    }
}
