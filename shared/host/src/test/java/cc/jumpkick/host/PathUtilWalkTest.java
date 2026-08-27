// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one tree walk, and the one stat.
 *
 * <p>The tree asked 1,194 metadata predicates against 17 {@code readAttributes}, and had 5
 * attribute-carrying walks against 233 blind ones. On Windows a raw walk is cheaper than on Linux —
 * {@code FindNextFileW} returns each entry's attributes with the entry — so the cost was never the
 * walk, it was discarding what it returned and re-resolving the path to ask again (JK-1031).
 */
class PathUtilWalkTest {

    @Test
    void it_visits_every_regular_file_with_its_attributes(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("a/b"));
        Files.writeString(tmp.resolve("top.txt"), "1234");
        Files.writeString(tmp.resolve("a/mid.txt"), "12345");
        Files.writeString(tmp.resolve("a/b/deep.txt"), "123456");

        List<String> seen = new ArrayList<>();
        long[] bytes = {0};
        PathUtil.forEachRegularFile(tmp, (file, attrs) -> {
            seen.add(tmp.relativize(file).toString().replace('\\', '/'));
            // The point: size comes from the walk, not from a second syscall.
            bytes[0] += attrs.size();
        });

        assertThat(seen).containsExactlyInAnyOrder("top.txt", "a/mid.txt", "a/b/deep.txt");
        assertThat(bytes[0]).isEqualTo(4 + 5 + 6);
    }

    @Test
    void directories_are_not_visited(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("empty/deeper"));
        Files.writeString(tmp.resolve("only.txt"), "x");

        List<Path> seen = new ArrayList<>();
        PathUtil.forEachRegularFile(tmp, (file, attrs) -> seen.add(file));

        assertThat(seen).containsExactly(tmp.resolve("only.txt"));
    }

    @Test
    void a_missing_root_is_a_no_op(@TempDir Path tmp) throws IOException {
        List<Path> seen = new ArrayList<>();
        PathUtil.forEachRegularFile(tmp.resolve("absent"), (file, attrs) -> seen.add(file));
        assertThat(seen).isEmpty();
    }

    @Test
    void a_symlink_is_not_followed(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.writeString(root.resolve("real.txt"), "real");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException noSymlinks) {
            return;
        }

        List<String> seen = new ArrayList<>();
        PathUtil.forEachRegularFile(
                root, (file, attrs) -> seen.add(file.getFileName().toString()));

        assertThat(seen).containsExactly("real.txt");
    }

    @Test
    void stat_answers_presence_size_and_kind_together(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("f.txt");
        Files.writeString(file, "seven!!");

        var attrs = PathUtil.stat(file);
        assertThat(attrs).isPresent();
        assertThat(attrs.get().isRegularFile()).isTrue();
        assertThat(attrs.get().size()).isEqualTo(7);

        assertThat(PathUtil.stat(tmp.resolve("nope"))).isEmpty();
        assertThat(PathUtil.stat(tmp).map(a -> a.isDirectory())).contains(true);
    }
}
