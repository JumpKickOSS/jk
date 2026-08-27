// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one tree copy. Twelve callers hand-rolled this and all twelve shared the same three defects
 * (JK-1032); these pin the behaviour that replaced them.
 */
class PathUtilCopyTreeTest {

    @Test
    void it_copies_a_tree(@TempDir Path tmp) throws IOException {
        Path from = Files.createDirectories(tmp.resolve("from/a/b"));
        Files.writeString(from.resolve("deep.txt"), "deep");
        Files.writeString(tmp.resolve("from/top.txt"), "top");
        Path to = tmp.resolve("to");

        PathUtil.copyTree(tmp.resolve("from"), to);

        assertThat(Files.readString(to.resolve("top.txt"))).isEqualTo("top");
        assertThat(Files.readString(to.resolve("a/b/deep.txt"))).isEqualTo("deep");
    }

    /**
     * The property the whole ticket is for: an unchanged target keeps its mtime.
     *
     * <p>Not a saving — {@code FreshnessStamp} compares classpath entries by mtime, so re-copying an
     * unchanged tree invalidated every downstream stamp and forced a full KSP round on every build.
     * {@code ActionCache.restoreArtifacts} learned that the hard way; ten of the twelve hand-rolled
     * copies could still do it.
     */
    @Test
    void an_identical_target_is_left_alone(@TempDir Path tmp) throws IOException {
        Path from = Files.createDirectories(tmp.resolve("from"));
        Files.writeString(from.resolve("same.txt"), "body");
        Path to = tmp.resolve("to");
        PathUtil.copyTree(from, to);

        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 600_000);
        Files.setLastModifiedTime(to.resolve("same.txt"), old);
        Files.setLastModifiedTime(from.resolve("same.txt"), old);

        PathUtil.copyTree(from, to);

        assertThat(Files.getLastModifiedTime(to.resolve("same.txt"))).isEqualTo(old);
    }

    @Test
    void a_differing_target_is_replaced(@TempDir Path tmp) throws IOException {
        Path from = Files.createDirectories(tmp.resolve("from"));
        Path to = Files.createDirectories(tmp.resolve("to"));
        Files.writeString(from.resolve("f.txt"), "new-and-longer");
        Files.writeString(to.resolve("f.txt"), "old");

        PathUtil.copyTree(from, to);

        assertThat(Files.readString(to.resolve("f.txt"))).isEqualTo("new-and-longer");
    }

    @Test
    void extras_survive_by_default_and_clean_target_removes_them(@TempDir Path tmp) throws IOException {
        Path from = Files.createDirectories(tmp.resolve("from"));
        Files.writeString(from.resolve("kept.txt"), "kept");
        Path to = Files.createDirectories(tmp.resolve("to"));
        Files.writeString(to.resolve("extra.txt"), "extra");

        PathUtil.copyTree(from, to);
        assertThat(to.resolve("extra.txt")).exists();

        PathUtil.copyTree(from, to, PathUtil.Copy.CLEAN_TARGET);
        assertThat(to.resolve("extra.txt")).doesNotExist();
        assertThat(Files.readString(to.resolve("kept.txt"))).isEqualTo("kept");
    }

    @Test
    void a_skipped_subtree_is_not_copied(@TempDir Path tmp) throws IOException {
        Path from = Files.createDirectories(tmp.resolve("from"));
        Files.createDirectories(from.resolve(".jk-scratch"));
        Files.writeString(from.resolve(".jk-scratch/junk.txt"), "junk");
        Files.writeString(from.resolve("real.txt"), "real");
        Path to = tmp.resolve("to");

        PathUtil.copyTree(
                from, to, dir -> dir.getFileName() != null && dir.getFileName().toString().startsWith(".jk-"));

        assertThat(to.resolve("real.txt")).exists();
        assertThat(to.resolve(".jk-scratch")).doesNotExist();
    }

    @Test
    void a_symlink_is_neither_followed_nor_recreated(@TempDir Path tmp) throws IOException {
        Path from = Files.createDirectories(tmp.resolve("from"));
        Files.writeString(from.resolve("real.txt"), "real");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(from.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException noSymlinks) {
            return; // Windows without developer mode
        }
        Path to = tmp.resolve("to");

        PathUtil.copyTree(from, to);

        assertThat(Files.readString(to.resolve("real.txt"))).isEqualTo("real");
        // Same reason G37 gives about deletes: a copy that follows a link writes somebody else's tree.
        assertThat(to.resolve("link/secret.txt")).doesNotExist();
    }

    @Test
    void a_missing_source_is_a_no_op(@TempDir Path tmp) throws IOException {
        PathUtil.copyTree(tmp.resolve("absent"), tmp.resolve("to"));
        assertThat(tmp.resolve("to")).doesNotExist();
    }

    @Test
    void preserve_attributes_carries_the_mtime_relationship(@TempDir Path tmp) throws IOException {
        // A scratch checkout where jk.toml must still look older than jk-lock.toml, so freshness
        // behaves as it did in the original tree.
        Path from = Files.createDirectories(tmp.resolve("from"));
        Path older = from.resolve("jk.toml");
        Path newer = from.resolve("jk-lock.toml");
        Files.writeString(older, "manifest");
        Files.writeString(newer, "lock");
        FileTime t1 = FileTime.fromMillis(System.currentTimeMillis() - 900_000);
        FileTime t2 = FileTime.fromMillis(System.currentTimeMillis() - 300_000);
        Files.setLastModifiedTime(older, t1);
        Files.setLastModifiedTime(newer, t2);
        Path to = tmp.resolve("to");

        PathUtil.copyTree(from, to, PathUtil.Copy.PRESERVE_ATTRIBUTES);

        assertThat(Files.getLastModifiedTime(to.resolve("jk.toml"))).isEqualTo(t1);
        assertThat(Files.getLastModifiedTime(to.resolve("jk-lock.toml"))).isEqualTo(t2);
    }
}
