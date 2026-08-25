// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathUtilTest {

    @Test
    void resolveUserPath_expands_tilde_and_home_relative_to_the_same_absolute() {
        Path home = PathUtil.userHome();
        Path fromTilde = PathUtil.resolveUserPath("~/src/oss/jk");
        Path fromRelative = PathUtil.resolveUserPath("src/oss/jk");
        assertThat(fromTilde).isEqualTo(home.resolve("src/oss/jk").normalize());
        assertThat(fromRelative).isEqualTo(fromTilde);
    }

    @Test
    void resolveUserPath_bare_tilde_is_home() {
        assertThat(PathUtil.resolveUserPath("~")).isEqualTo(PathUtil.userHome());
        assertThat(PathUtil.resolveUserPath("~/")).isEqualTo(PathUtil.userHome());
    }

    @Test
    void resolveUserPath_keeps_absolute_paths() {
        Path abs = Path.of("/tmp/jk-workspace").toAbsolutePath().normalize();
        assertThat(PathUtil.resolveUserPath(abs.toString())).isEqualTo(abs);
        assertThat(PathUtil.resolveUserPath("  " + abs + "  ")).isEqualTo(abs);
    }

    @Test
    void resolveUserPath_does_not_expand_other_users_tilde() {
        // "~alice/…" is a relative path segment starting with ~alice, not home expansion.
        Path got = PathUtil.resolveUserPath("~alice/project");
        assertThat(got).isEqualTo(PathUtil.userHome().resolve("~alice/project").normalize());
    }

    @Test
    void resolveUserPath_rejects_blank() {
        assertThatThrownBy(() -> PathUtil.resolveUserPath(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> PathUtil.resolveUserPath("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathUtil.resolveUserPath(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- deleteRecursively: a link is removed, never entered -------------------------------
    //
    // These exist because the rule is load-bearing and was invisible. `Files.walk` and
    // `Files.walkFileTree` both decline to follow links by default, so the old implementation was
    // correct for links it met inside a tree — but nothing said so and nothing checked it, and the
    // way to "fix" a cleanup that leaves files behind is to add FileVisitOption.FOLLOW_LINKS. That
    // one constant would turn this method into something that empties whatever a link points at.

    /** A link inside the tree goes; what it points at does not. */
    @Test
    void deleteRecursively_unlinks_and_leaves_the_target(@TempDir Path tmp) throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside/deep"));
        Path keep = Files.writeString(outside.getParent().resolve("keep.txt"), "precious");
        Path deep = Files.writeString(outside.resolve("d.txt"), "deeper");

        Path tree = Files.createDirectories(tmp.resolve("tree"));
        Files.createSymbolicLink(tree.resolve("to-dir"), outside.getParent());
        Files.createSymbolicLink(tree.resolve("to-file"), keep);
        Files.writeString(tree.resolve("own.txt"), "mine");

        PathUtil.deleteRecursively(tree);

        assertThat(tree).as("the tree itself").doesNotExist();
        assertThat(keep).as("a linked-to file").exists().hasContent("precious");
        assertThat(deep).as("a file under a linked-to directory").exists().hasContent("deeper");
    }

    /** A link handed in as the root is removed, not walked into. */
    @Test
    void deleteRecursively_removes_a_link_root_without_touching_its_target(@TempDir Path tmp) throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path keep = Files.writeString(outside.resolve("keep.txt"), "precious");
        Path link = Files.createSymbolicLink(tmp.resolve("link"), outside);

        PathUtil.deleteRecursively(link);

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).as("the link").isFalse();
        assertThat(outside).as("its target directory").isDirectory();
        assertThat(keep).exists().hasContent("precious");
    }

    /**
     * A dangling link is still a link to remove. This was the one case the old implementation got
     * wrong: it gated on {@code Files.exists}, which follows, so a broken link answered "absent"
     * and survived every cleanup.
     */
    @Test
    void deleteRecursively_removes_a_dangling_link(@TempDir Path tmp) throws Exception {
        Path dangling = Files.createSymbolicLink(tmp.resolve("dangling"), tmp.resolve("never-existed"));
        assertThat(Files.exists(dangling)).as("follows to nothing").isFalse();

        PathUtil.deleteRecursively(dangling);
        assertThat(Files.exists(dangling, LinkOption.NOFOLLOW_LINKS))
                .as("the link")
                .isFalse();

        Path tree = Files.createDirectories(tmp.resolve("tree"));
        Files.createSymbolicLink(tree.resolve("broken"), tmp.resolve("never-existed"));
        PathUtil.deleteRecursively(tree);
        assertThat(tree).as("a tree whose only child was a broken link").doesNotExist();
    }

    /** Same rule on the throwing arm, and the tally sizes the link rather than its target. */
    @Test
    void deleteRecursivelyOrThrow_unlinks_and_counts_the_link_not_the_target(@TempDir Path tmp) throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path big = Files.writeString(outside.resolve("big.txt"), "x".repeat(10_000));

        Path tree = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(tree.resolve("own.txt"), "1234567890"); // 10 bytes
        Files.createSymbolicLink(tree.resolve("to-big"), big);

        var tally = new PathUtil.Removed();
        PathUtil.deleteRecursivelyOrThrow(tree, tally);

        assertThat(big).as("the linked-to file").exists();
        assertThat(tally.bytes())
                .as("10 bytes of our own file; the 10,000-byte link target is not space we freed")
                .isEqualTo(10);
    }

    /** A missing root is a no-op on both arms — cleanup runs against paths that may already be gone. */
    @Test
    void deleting_an_absent_root_is_a_no_op(@TempDir Path tmp) throws Exception {
        PathUtil.deleteRecursively(tmp.resolve("nope"));
        PathUtil.deleteRecursivelyOrThrow(tmp.resolve("nope"));
        PathUtil.deleteRecursively(null);
    }
}
