// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.ReadUsage;
import cc.jumpkick.config.TomlValues;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The first command at a terminal says that jk reads agents' token counts and how to stop it, once. */
class ReadUsageHintTest {

    @TempDir
    Path dir;

    @Test
    void the_notice_shows_once_with_how_to_turn_it_off() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(err, true, StandardCharsets.UTF_8);
        Path config = dir.resolve("config.toml");
        assertThat(ReadUsageHint.show(config, dir.resolve("state"), out)).isTrue();
        assertThat(ReadUsageHint.show(config, dir.resolve("state"), out)).isFalse();
        assertThat(err.toString(StandardCharsets.UTF_8))
                .isEqualTo(ReadUsage.NOTICE + System.lineSeparator() + "    " + ReadUsage.OFF + System.lineSeparator());
    }

    @Test
    void nothing_shows_when_reading_is_off() throws IOException {
        Path config = dir.resolve("config.toml");
        Files.writeString(config, "[agents]\nread-usage = false\n");
        TomlValues.clearCache();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(ReadUsageHint.show(config, dir.resolve("state"), new PrintStream(err, true, StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(err.size()).isZero();
    }
}
