// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JkHttpConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The engine's sessions expire after {@code [mcp] session-expiry} whether or not it serves HTTP. */
class EngineSessionExpiryTest extends EngineServerHarness {

    @Test
    void session_expiry_is_honoured_with_http_off() throws Exception {
        Path home = shortTempDir();
        Path userConfig = Files.writeString(
                home.resolve("config.toml"), "[http]\nenabled = false\n\n[mcp]\nsession-expiry = \"8h\"\n");
        assertThat(JkHttpConfig.fromToml(userConfig)).as("HTTP is off").isEmpty();

        EngineServer server =
                new EngineServer(paths(shortTempDir()), JkEngineConfig.DEFAULTS, null, "1.0", "", null, userConfig);
        try {
            assertThat(server.sessionsForTests().expiryMillis())
                    .isEqualTo(Duration.ofHours(8).toMillis());
        } finally {
            server.close();
        }
    }
}
