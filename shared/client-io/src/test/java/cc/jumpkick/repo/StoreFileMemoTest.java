// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreFileMemoTest {

    private static final StoreFileMemo.Reader<String> READ = f -> Files.readString(f, StandardCharsets.UTF_8);

    @Test
    void an_unchanged_file_is_read_once(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("a.pom"), "one");
        StoreFileMemo<String> memo = new StoreFileMemo<>(100, v -> 1);

        assertThat(memo.get(file, READ)).hasValue("one");
        assertThat(memo.get(file, READ)).hasValue("one");
        assertThat(memo.get(tmp.resolve(".").resolve("a.pom"), READ))
                .as("the same file by another spelling")
                .hasValue("one");

        assertThat(memo.reads()).isEqualTo(1);
    }

    @Test
    void a_file_replaced_by_rename_is_read_again(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("a.pom"), "one");
        StoreFileMemo<String> memo = new StoreFileMemo<>(100, v -> 1);
        assertThat(memo.get(file, READ)).hasValue("one");

        // The store writes a sibling and renames it over the old name, as here: same size, a new file.
        // On Windows the new file differs from the old one by its creation time alone, which the clock
        // stamps by the tick: written within the old file's tick it would be the old file to every
        // attribute, so the sibling is written again until its tick is another.
        Path next = Files.writeString(tmp.resolve("a.pom.part"), "two");
        for (int i = 0; i < 100 && sameCreationTime(next, file); i++) {
            Thread.sleep(5);
            Files.delete(next);
            Files.writeString(next, "two");
        }
        Files.setLastModifiedTime(next, Files.getLastModifiedTime(file));
        AtomicWrites.moveInto(next, file);

        assertThat(memo.get(file, READ)).hasValue("two");
        assertThat(memo.reads()).isEqualTo(2);
    }

    private static boolean sameCreationTime(Path a, Path b) throws IOException {
        return Files.readAttributes(a, BasicFileAttributes.class)
                .creationTime()
                .equals(Files.readAttributes(b, BasicFileAttributes.class).creationTime());
    }

    @Test
    void a_missing_file_answers_empty_and_forgets_what_it_held(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("a.pom"), "one");
        StoreFileMemo<String> memo = new StoreFileMemo<>(100, v -> 1);
        assertThat(memo.get(file, READ)).hasValue("one");

        Files.delete(file);

        assertThat(memo.get(file, READ)).isEmpty();
        Files.writeString(file, "one");
        assertThat(memo.get(file, READ)).hasValue("one");
        assertThat(memo.reads()).isEqualTo(2);
    }

    @Test
    void past_its_weight_the_least_recently_read_file_is_read_again(@TempDir Path tmp) throws Exception {
        Path a = Files.writeString(tmp.resolve("a.pom"), "a");
        Path b = Files.writeString(tmp.resolve("b.pom"), "b");
        Path c = Files.writeString(tmp.resolve("c.pom"), "c");
        StoreFileMemo<String> memo = new StoreFileMemo<>(2, v -> 1);
        memo.get(a, READ);
        memo.get(b, READ);
        memo.get(a, READ);
        memo.get(c, READ); // b goes

        long before = memo.reads();
        memo.get(a, READ);
        assertThat(memo.reads()).isEqualTo(before);
        memo.get(b, READ);
        assertThat(memo.reads()).isEqualTo(before + 1);
    }
}
