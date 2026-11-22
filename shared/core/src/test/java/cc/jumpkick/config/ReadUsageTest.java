// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [agents] read-usage}: on unless the file says false, and its notice once per surface. */
class ReadUsageTest {

    @TempDir
    Path dir;

    private boolean on(String toml) throws IOException {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, toml);
        TomlValues.clearCache();
        return ReadUsage.on(file);
    }

    @Test
    void reading_is_on_unless_the_file_turns_it_off() throws IOException {
        assertThat(ReadUsage.on(dir.resolve("missing.toml"))).isTrue();
        assertThat(on("")).isTrue();
        assertThat(on("[agents]\nread-usage = true\n")).isTrue();
        assertThat(on("[agents]\nread-usage = \"no\"\n")).as("not a boolean").isTrue();
        assertThat(on("[agents]\nread-usage = false\n")).isFalse();
    }

    @Test
    void each_surface_gets_its_notice_once() {
        Path state = dir.resolve("state");
        assertThat(ReadUsage.firstNotice(state, ReadUsage.CLI)).isTrue();
        assertThat(ReadUsage.firstNotice(state, ReadUsage.CLI)).isFalse();
        assertThat(ReadUsage.firstNotice(state, ReadUsage.WEB)).isTrue();
        assertThat(ReadUsage.firstNotice(state, ReadUsage.WEB)).isFalse();
    }

    @Test
    void the_notice_fits_a_line() {
        assertThat(ReadUsage.NOTICE.length()).isLessThanOrEqualTo(78);
        assertThat(("    " + ReadUsage.OFF).length()).isLessThanOrEqualTo(78);
    }
}
