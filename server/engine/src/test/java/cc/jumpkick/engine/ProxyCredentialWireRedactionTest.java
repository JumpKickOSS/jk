// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.http.ProxyEnvironment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production proxy selector, reading {@code [network]} from a scratch {@code ~/.jk/config.toml},
 * hands a tool the proxy URL with its password; the tool's failure quotes it; the engine's event
 * redactor, which every wire event and so every {@code -o jsonl} line passes through, masks it.
 */
class ProxyCredentialWireRedactionTest {

    private static final String PASSWORD = "wire-proxy-secret";

    @BeforeEach
    @AfterEach
    void forget() {
        ResolvedSecrets.clear();
    }

    @Test
    void a_network_proxy_password_a_tool_quotes_is_masked_on_the_wire(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("jk-home"));
        Files.writeString(
                home.resolve("config.toml"),
                "[network]\nproxy = \"http://builder:" + PASSWORD + "@proxy.corp:3128\"\n");
        Path project = Files.createDirectories(tmp.resolve("project"));
        String failure;
        System.setProperty("jk.env.JK_HOME", home.toString());
        try {
            String url =
                    SessionContext.where(Session.defaults().withWorkingDir(project), () -> ProxyEnvironment.ambient()
                            .proxyUrl(URI.create("https://registry.npmjs.org/left-pad"))
                            .orElseThrow());
            failure = "npm error request failed, reason: proxy " + url + " answered 407";
        } finally {
            System.clearProperty("jk.env.JK_HOME");
        }
        assertThat(failure).contains(PASSWORD);

        String onTheWire = requireNonNull(EventRedaction.redactEnv(project.toString(), failure));

        assertThat(onTheWire)
                .doesNotContain(PASSWORD)
                .contains(SecretRedactor.MASK)
                .contains("builder");
    }
}
