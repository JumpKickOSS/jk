// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiAskTest {

    @Test
    void the_env_wins_over_the_file_and_anything_unreadable_falls_through_to_notify(@TempDir Path dir)
            throws IOException {
        Path toml = dir.resolve("config.toml");
        assertThat(UiAsk.resolve(toml, Map.<String, String>of()::get))
                .as("no file")
                .isEqualTo(UiAsk.NOTIFY);
        Files.writeString(toml, "[ui]\nask = \"quiet\"\n");
        assertThat(UiAsk.resolve(toml, Map.<String, String>of()::get)).isEqualTo(UiAsk.QUIET);
        assertThat(UiAsk.resolve(toml, Map.of("JK_UI_ASK", "OPEN")::get)).isEqualTo(UiAsk.OPEN);
        assertThat(UiAsk.resolve(toml, Map.of("JK_UI_ASK", "loud")::get))
                .as("an unreadable env value falls through to the file")
                .isEqualTo(UiAsk.QUIET);
        Files.writeString(toml, "[ui]\nask = \"sometimes\"\n");
        assertThat(UiAsk.resolve(toml, Map.<String, String>of()::get)).isEqualTo(UiAsk.NOTIFY);
        assertThat(UiAsk.OPEN.wire()).isEqualTo("open");
    }
}
