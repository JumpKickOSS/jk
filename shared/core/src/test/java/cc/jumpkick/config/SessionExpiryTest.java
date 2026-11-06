// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionExpiryTest {

    private static Duration fromFile(Path toml) {
        return SessionExpiry.resolve(toml, Map.<String, String>of()::get);
    }

    @Test
    void reads_a_duration_from_the_file_and_the_env_and_refuses_zero(@TempDir Path tempDir) throws IOException {
        assertThat(SessionExpiry.DEFAULT).isEqualTo(Duration.ofHours(24));
        Path toml = tempDir.resolve("config.toml");
        assertThat(fromFile(toml)).as("no file").isEqualTo(SessionExpiry.DEFAULT);
        Files.writeString(toml, "[mcp]\nsession-expiry = \"90m\"\n");
        assertThat(fromFile(toml)).isEqualTo(Duration.ofMinutes(90));
        Files.writeString(toml, "[mcp]\nsession-expiry = 600\n");
        assertThat(fromFile(toml)).as("a bare number is seconds").isEqualTo(Duration.ofMinutes(10));
        assertThat(SessionExpiry.resolve(toml, Map.of("JK_MCP_SESSION_EXPIRY", "7d")::get))
                .isEqualTo(Duration.ofDays(7));
        assertThat(SessionExpiry.resolve(toml, Map.of("JK_MCP_SESSION_EXPIRY", "soon")::get))
                .as("an unreadable env value falls through to the file")
                .isEqualTo(Duration.ofMinutes(10));
        Files.writeString(toml, "[mcp]\nsession-expiry = \"0h\"\n");
        assertThat(fromFile(toml)).isEqualTo(SessionExpiry.DEFAULT);
        assertThat(SessionExpiry.spell(Duration.ofHours(24))).isEqualTo("24h");
        assertThat(SessionExpiry.spell(Duration.ofDays(7))).isEqualTo("7d");
        assertThat(SessionExpiry.spell(Duration.ofMinutes(90))).isEqualTo("90m");
    }

    @Test
    void is_honoured_with_http_and_mcp_off(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nenabled = false\n\n[mcp]\nenabled = false\nsession-expiry = \"8h\"\n");
        assertThat(JkHttpConfig.fromToml(toml)).as("the server is off").isEmpty();
        assertThat(fromFile(toml)).isEqualTo(Duration.ofHours(8));
        assertThat(SessionExpiry.resolve(toml, Map.of("JK_HTTP_ENABLED", "false")::get))
                .isEqualTo(Duration.ofHours(8));
    }
}
