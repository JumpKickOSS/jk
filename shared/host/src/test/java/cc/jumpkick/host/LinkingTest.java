// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinkingTest {

    @Test
    void same_filesystem_creates_a_hard_link(@TempDir Path tempDir) throws IOException {
        Path src = Files.writeString(tempDir.resolve("src"), "hello");
        Path dst = tempDir.resolve("dst");

        Linking.linkOrCopy(src, dst);

        assertThat(Files.exists(dst)).isTrue();
        assertThat(Files.readString(dst)).isEqualTo("hello");
        // Same-fs link → same inode (BasicFileAttributes.fileKey() is the
        // best portable proxy we have for "shared inode").
        var srcKey = Files.readAttributes(src, BasicFileAttributes.class).fileKey();
        var dstKey = Files.readAttributes(dst, BasicFileAttributes.class).fileKey();
        assertThat(dstKey).as("hard-link should share an inode with the source").isEqualTo(srcKey);
    }

    @Test
    void replaces_existing_target(@TempDir Path tempDir) throws IOException {
        Path src = Files.writeString(tempDir.resolve("src"), "new");
        Path dst = Files.writeString(tempDir.resolve("dst"), "old");

        Linking.linkOrCopy(src, dst);

        assertThat(Files.readString(dst)).isEqualTo("new");
    }

    @Test
    void creates_parent_directories(@TempDir Path tempDir) throws IOException {
        Path src = Files.writeString(tempDir.resolve("src"), "hi");
        Path dst = tempDir.resolve("a").resolve("b").resolve("c").resolve("dst");

        Linking.linkOrCopy(src, dst);

        assertThat(Files.exists(dst)).isTrue();
        assertThat(Files.readString(dst)).isEqualTo("hi");
    }

    /**
     * The link-capability question is answered once per volume pair, not once per file.
     *
     * <p>On a mount that refuses hard links, a failed {@code createLink} is 79.6 µs on Windows
     * (17x Linux) before the 305 µs copy. Only *negatives* are cached: a pair that linked once may
     * still fail later (permissions, a full volume), and re-trying costs a failed createLink rather
     * than a wrong answer, whereas caching a positive and being wrong would turn a copy into a
     * link.
     */
    @Test
    void repeated_materialisation_still_produces_the_bytes(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("blob");
        Files.writeString(src, "payload");
        for (int i = 0; i < 20; i++) {
            Path dst = dir.resolve("out-" + i + ".jar");
            Linking.linkOrCopy(src, dst);
            assertThat(Files.readString(dst)).isEqualTo("payload");
        }
    }

    @Test
    void it_replaces_an_existing_target(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("new");
        Files.writeString(src, "new-bytes");
        Path dst = dir.resolve("dst");
        Files.writeString(dst, "stale");

        Linking.linkOrCopy(src, dst);

        assertThat(Files.readString(dst)).isEqualTo("new-bytes");
    }

    /**
     * Two materialisers of one target can interleave between the delete and the link. The loser's
     * {@code FileAlreadyExistsException} says nothing about the volume pair: it must retry, win, and
     * leave every later materialisation on the pair a link rather than a copy.
     */
    @Test
    void a_lost_race_for_the_target_retries_and_leaves_the_pair_linkable(@TempDir Path dir) throws IOException {
        Linking.forgetVerdicts();
        Path src = Files.writeString(dir.resolve("blob"), "payload");
        Path dst = dir.resolve("out.jar");

        Linking.linkOrCopy(src, dst, () -> Files.writeString(dst, "the other materialiser's bytes"));

        assertThat(Files.readString(dst)).isEqualTo("payload");
        assertThat(fileKey(dst)).as("the retry linked rather than copied").isEqualTo(fileKey(src));
        Path next = dir.resolve("next.jar");
        Linking.linkOrCopy(src, next);
        assertThat(fileKey(next))
                .as("the pair is still linkable after the race")
                .isEqualTo(fileKey(src));
    }

    @Test
    void a_missing_source_propagates_and_leaves_the_pair_linkable(@TempDir Path dir) throws IOException {
        Linking.forgetVerdicts();
        Path missing = dir.resolve("pruned-blob");
        assertThatThrownBy(() -> Linking.linkOrCopy(missing, dir.resolve("out.jar")))
                .isInstanceOf(NoSuchFileException.class);

        Path src = Files.writeString(dir.resolve("blob"), "payload");
        Path next = dir.resolve("next.jar");
        Linking.linkOrCopy(src, next);
        assertThat(fileKey(next)).as("the pair is still linkable").isEqualTo(fileKey(src));
    }

    @Test
    void only_a_verdict_about_the_volume_pair_is_remembered() {
        assertThat(Linking.isVolumeVerdict(new FileSystemException("a", "b", "Invalid cross-device link")))
                .isTrue();
        assertThat(Linking.isVolumeVerdict(new FileSystemException("a", "b", "Operation not supported")))
                .isTrue();
        assertThat(Linking.isVolumeVerdict(new FileSystemException("a", "b", "Operation not permitted")))
                .isTrue();
        assertThat(Linking.isVolumeVerdict(new FileSystemException("a", "b", "The request is not supported")))
                .isTrue();
        assertThat(Linking.isVolumeVerdict(new FileAlreadyExistsException("a"))).isFalse();
        assertThat(Linking.isVolumeVerdict(new NoSuchFileException("a"))).isFalse();
        assertThat(Linking.isVolumeVerdict(new FileSystemException("a", "b", "Too many links")))
                .isFalse();
        assertThat(Linking.isVolumeVerdict(new FileSystemException("a")))
                .as("no reason, no verdict")
                .isFalse();
    }

    private static Object fileKey(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class).fileKey();
    }
}
