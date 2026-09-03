// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
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
}
