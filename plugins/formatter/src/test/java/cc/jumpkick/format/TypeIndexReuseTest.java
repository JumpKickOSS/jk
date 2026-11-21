// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A served worker's next scan reads only the sources that changed: one whose size and modification
 * time are those of the last read answers from memory, and one that moved is read again.
 */
class TypeIndexReuseTest {

    @Test
    void a_source_whose_stamp_held_is_not_read_again(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("A.java");
        Files.writeString(file, "package p;\npublic class Aaa {}\n");
        FileTime stamp = Files.getLastModifiedTime(file);
        assertThat(TypeIndex.scan(List.of(file), false).contains("p.Aaa")).isTrue();

        // Same size, same modification time: only a re-read could see the new name.
        Files.writeString(file, "package p;\npublic class Bbb {}\n");
        Files.setLastModifiedTime(file, stamp);

        TypeIndex again = TypeIndex.scan(List.of(file), false);
        assertThat(again.contains("p.Aaa")).isTrue();
        assertThat(again.contains("p.Bbb")).isFalse();
    }

    @Test
    void a_source_that_changed_is_read_again(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("A.java");
        Files.writeString(file, "package p;\npublic class Aaa {}\n");
        FileTime stamp = Files.getLastModifiedTime(file);
        assertThat(TypeIndex.scan(List.of(file), false).contains("p.Aaa")).isTrue();

        Files.writeString(file, "package p;\npublic class Renamed {}\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp.toMillis() + 2_000));

        TypeIndex again = TypeIndex.scan(List.of(file), false);
        assertThat(again.contains("p.Renamed")).isTrue();
        assertThat(again.contains("p.Aaa")).isFalse();
    }

    @Test
    void a_deleted_source_declares_nothing(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("A.java");
        Files.writeString(file, "package p;\npublic class Aaa {}\n");
        assertThat(TypeIndex.scan(List.of(file), false).contains("p.Aaa")).isTrue();

        Files.delete(file);

        assertThat(TypeIndex.scan(List.of(file), false).contains("p.Aaa")).isFalse();
    }
}
