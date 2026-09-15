// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextFilesTest {

    @Test
    void a_file_over_the_size_cap_is_outside_the_corpus(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Small.java"), "class Small {}\n");
        byte[] big = new byte[(int) TextFiles.MAX_BYTES + 1];
        Arrays.fill(big, (byte) 'x');
        Files.write(root.resolve("src/model.json"), big);
        byte[] atCap = new byte[(int) TextFiles.MAX_BYTES];
        Arrays.fill(atCap, (byte) 'y');
        Files.write(root.resolve("src/at-cap.txt"), atCap);
        assertThat(TextFiles.corpus(root))
                .extracting(TextFiles.Entry::rel)
                .containsExactly("src/Small.java", "src/at-cap.txt");
    }

    /** A linked git worktree has a {@code .git} pointer file where a checkout has a directory. */
    @Test
    void a_git_pointer_file_is_outside_the_corpus_like_the_directory(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Small.java"), "class Small {}\n");
        Files.writeString(root.resolve(".git"), "gitdir: /somewhere/.git/worktrees/feature-branch\n");
        assertThat(TextFiles.corpus(root)).extracting(TextFiles.Entry::rel).containsExactly("src/Small.java");
    }

    @Test
    void a_nul_byte_marks_a_file_binary_before_it_is_decoded(@TempDir Path root) throws IOException {
        Path text = root.resolve("Ok.java");
        Files.writeString(text, "class Ok { String s = \"héllo\"; }\n");
        Path binary = root.resolve("model.dat");
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) 'a');
        bytes[100] = 0;
        Files.write(binary, bytes);
        Path invalid = root.resolve("latin1.txt");
        Files.write(invalid, new byte[] {(byte) 'c', (byte) 'a', (byte) 0xE9});
        assertThat(TextFiles.read(text)).isEqualTo("class Ok { String s = \"héllo\"; }\n");
        assertThat(TextFiles.read(binary)).isNull();
        assertThat(TextFiles.read(invalid)).as("not UTF-8 either").isNull();
        assertThat(TextFiles.sniff(Arrays.copyOf(bytes, TextFiles.SNIFF_BYTES))).isFalse();
        assertThat(TextFiles.sniff("plain".getBytes(StandardCharsets.UTF_8))).isTrue();
    }
}
