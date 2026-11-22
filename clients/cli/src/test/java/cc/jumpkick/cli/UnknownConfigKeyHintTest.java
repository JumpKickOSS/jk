// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.toml key this jk does not read gets one stderr line, the first time, then silence. */
class UnknownConfigKeyHintTest {

    @TempDir
    Path dir;

    @Test
    void each_unknown_key_is_named_once() throws IOException {
        Path config = Files.writeString(dir.resolve("config.toml"), "[engine]\nmax-heap-mb = 512\nheap-mb = 512\n");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(err, true, StandardCharsets.UTF_8);

        assertThat(UnknownConfigKeyHint.show(config, dir.resolve("state"), out)).isEqualTo(1);
        assertThat(UnknownConfigKeyHint.show(config, dir.resolve("state"), out)).isZero();

        assertThat(err.toString(StandardCharsets.UTF_8))
                .isEqualTo("jk: engine.heap-mb: unknown key in " + config + ", ignored" + System.lineSeparator());
    }

    @Test
    void a_file_of_known_keys_says_nothing() throws IOException {
        Path config = Files.writeString(dir.resolve("config.toml"), "[engine]\nmax-heap-mb = 512\n");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertThat(UnknownConfigKeyHint.show(
                        config, dir.resolve("state"), new PrintStream(err, true, StandardCharsets.UTF_8)))
                .isZero();
        assertThat(err.size()).isZero();
    }
}
