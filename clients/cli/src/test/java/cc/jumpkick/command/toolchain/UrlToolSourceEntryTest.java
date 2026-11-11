// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A cached remote script reruns the file that was its entry, and a half-written cache is fetched again. */
class UrlToolSourceEntryTest {

    @Test
    void the_cached_entry_is_the_recorded_one_not_the_first_by_name(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Helper.java"), "class Helper {}\n");
        Files.writeString(dir.resolve("tool.java"), "class tool {}\n");
        Files.writeString(dir.resolve(UrlToolSource.ENTRY), "tool.java\n");

        assertThat(UrlToolSource.cachedEntry(dir)).isEqualTo(dir.resolve("tool.java"));
    }

    @Test
    void a_cache_directory_without_its_entry_mark_is_fetched_again(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("tool.java"), "class tool {}\n");

        assertThat(UrlToolSource.cachedEntry(dir)).isNull();
    }
}
