// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [agents] read-usage}: on unless the environment or the file says false, and its notice once. */
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
    void the_environment_overrides_the_file() throws IOException {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, "[agents]\nread-usage = false\n");
        TomlValues.clearCache();
        assertThat(ReadUsage.on(file, Map.of(ReadUsage.ENV, "true")::get)).isTrue();
        assertThat(ReadUsage.on(file, Map.of(ReadUsage.ENV, "maybe")::get))
                .as("not a boolean")
                .isFalse();
        assertThat(ReadUsage.on(dir.resolve("missing.toml"), Map.of(ReadUsage.ENV, "false")::get))
                .isFalse();
        assertThat(ReadUsage.on(dir.resolve("missing.toml"), Map.<String, String>of()::get))
                .isTrue();
    }

    @Test
    void a_machine_that_saw_the_earlier_wording_sees_this_one_once() throws IOException {
        Path state = dir.resolve("state");
        Files.createDirectories(state);
        Files.createFile(state.resolve("read-usage-noticed-web"));
        assertThat(ReadUsage.firstNotice(state)).isTrue();
        assertThat(ReadUsage.firstNotice(state)).isFalse();
    }

    @Test
    void the_notice_fits_a_line() {
        assertThat(ReadUsage.NOTICE.length()).isLessThanOrEqualTo(78);
        assertThat(ReadUsage.OFF.length()).isLessThanOrEqualTo(78);
    }
}
