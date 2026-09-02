// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one answer to "may jk delete this?", which three sites used to give for themselves — two of
 * them wrongly, at the cost of four real JDK installations.
 */
class JkOwnershipTest {

    private static Path populated(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("payload.txt"), "irreplaceable\n");
        return dir;
    }

    @Test
    void a_populated_directory_jk_did_not_create_is_refused_by_name(@TempDir Path tmp) throws IOException {
        Path theirs = populated(tmp.resolve("graalvm-25"));

        assertThatIOException()
                .isThrownBy(() -> JkOwnership.removeIfOwned(theirs))
                .withMessageContaining("graalvm-25")
                .withMessageContaining("jk did not create it");

        assertThat(theirs.resolve("payload.txt")).exists();
    }

    @Test
    void a_populated_directory_jk_created_is_removed(@TempDir Path tmp) throws IOException {
        Path ours = populated(tmp.resolve("temurin-25"));
        JkOwnership.mark(ours);
        assertThat(JkOwnership.isOwned(ours)).isTrue();

        JkOwnership.removeIfOwned(ours);

        assertThat(ours).doesNotExist();
    }

    @Test
    void an_empty_directory_needs_no_marker(@TempDir Path tmp) throws IOException {
        // Nothing to lose, so nothing to protect: an empty directory is not somebody's data.
        Path empty = Files.createDirectory(tmp.resolve("empty"));
        JkOwnership.removeIfOwned(empty);
        assertThat(empty).doesNotExist();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void a_link_is_unlinked_and_its_target_is_left_alone(@TempDir Path tmp) throws IOException {
        // The half fixed, pinned here too because this is now the site that decides it: a
        // pointer jk planted into ~/.sdkman must cost the link, never the JDK behind it.
        Path theirs = populated(tmp.resolve("sdkman-candidate"));
        Path link = tmp.resolve("pointer");
        Files.createSymbolicLink(link, theirs);

        JkOwnership.removeIfOwned(link);

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(theirs.resolve("payload.txt"))
                .as("the target keeps its contents")
                .exists();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void a_dangling_link_is_removed_rather_than_mistaken_for_absent(@TempDir Path tmp) throws IOException {
        Path link = tmp.resolve("dangling");
        Files.createSymbolicLink(link, tmp.resolve("never-existed"));

        JkOwnership.removeIfOwned(link);

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void a_path_that_is_not_there_is_a_no_op(@TempDir Path tmp) {
        assertThatCode(() -> JkOwnership.removeIfOwned(tmp.resolve("absent"))).doesNotThrowAnyException();
        assertThatCode(() -> JkOwnership.removeIfOwned(null)).doesNotThrowAnyException();
    }

    @Test
    void marking_is_idempotent_and_never_throws(@TempDir Path tmp) throws IOException {
        Path dir = populated(tmp.resolve("twice"));
        JkOwnership.mark(dir);
        JkOwnership.mark(dir);
        assertThat(JkOwnership.isOwned(dir)).isTrue();
        assertThatCode(() -> JkOwnership.mark(null)).doesNotThrowAnyException();
    }

    @Test
    void an_unmarked_directory_is_not_owned(@TempDir Path tmp) throws IOException {
        assertThat(JkOwnership.isOwned(populated(tmp.resolve("plain")))).isFalse();
        assertThat(JkOwnership.isOwned(null)).isFalse();
    }
}
