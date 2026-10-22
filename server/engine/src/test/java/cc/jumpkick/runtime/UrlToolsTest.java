// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step-dependency at a URL is pinned by its sha256: {@code jk lock} downloads it into the store,
 * and a build reads that copy while it is the pinned bytes, asking the server nothing. Other
 * bytes, stored or served, are never handed to a step, and an offline build with no pinned copy
 * fails naming the URL rather than reaching out.
 */
class UrlToolsTest {

    private static final String RULES = "<module name=\"Checker\"/>";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @Test
    void a_pinned_copy_is_read_from_the_store(@TempDir Path tmp) throws Exception {
        List<String> requests = new ArrayList<>();
        http.beforeServe(requests::add).serve("/build/rules.xml", RULES);
        Cas cas = new Cas(tmp.resolve("store"));
        String url = http.base().resolve("build/rules.xml").toString();

        Path locked = UrlTools.download(url, cas);
        Path built = UrlTools.fetch(url, cas, Hashing.sha256Hex(RULES.getBytes(StandardCharsets.UTF_8)));

        assertThat(built).isEqualTo(locked).isRegularFile().hasFileName("rules.xml");
        assertThat(built).startsWith(cas.root().resolve("tools").resolve("url"));
        assertThat(Files.readString(built)).isEqualTo(RULES);
        assertThat(requests).hasSize(1);
    }

    @Test
    void a_stored_copy_with_other_bytes_is_fetched_again_and_verified(@TempDir Path tmp) throws Exception {
        http.serve("/build/rules.xml", RULES);
        Cas cas = new Cas(tmp.resolve("store"));
        String url = http.base().resolve("build/rules.xml").toString();
        Path stored = UrlTools.download(url, cas);
        Files.writeString(stored, "tampered");

        Path built = UrlTools.fetch(url, cas, Hashing.sha256Hex(RULES.getBytes(StandardCharsets.UTF_8)));

        assertThat(Files.readString(built)).isEqualTo(RULES);
    }

    @Test
    void a_server_serving_other_bytes_than_the_pin_is_refused(@TempDir Path tmp) {
        http.serve("/build/rules.xml", RULES);
        Cas cas = new Cas(tmp.resolve("store"));
        String url = http.base().resolve("build/rules.xml").toString();
        String pinned = "0".repeat(64);

        assertThatThrownBy(() -> UrlTools.fetch(url, cas, pinned))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-lock.toml pins sha256 " + pinned + " for " + url)
                .hasMessageContaining("jk lock -F");
    }

    @Test
    void offline_with_no_pinned_copy_fails_naming_the_url(@TempDir Path tmp) throws Exception {
        http.serve("/build/rules.xml", RULES);
        Cas cas = new Cas(tmp.resolve("store"));
        String url = http.base().resolve("build/rules.xml").toString();
        String sha256 = Hashing.sha256Hex(RULES.getBytes(StandardCharsets.UTF_8));
        Session offline = Session.defaults().withConfig(JkConfig.empty().withOffline(true));

        assertThatThrownBy(() -> SessionContext.where(offline, () -> UrlTools.fetch(url, cas, sha256)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(url)
                .hasMessageContaining("--offline");
        assertThat(http.served()).containsKey("/build/rules.xml");

        Path fetched = UrlTools.fetch(url, cas, sha256);
        assertThat(SessionContext.where(offline, () -> UrlTools.fetch(url, cas, sha256)))
                .as("a pinned copy is read offline")
                .isEqualTo(fetched);
    }

    @Test
    void a_status_other_than_200_is_a_failure_naming_it(@TempDir Path tmp) {
        Cas cas = new Cas(tmp.resolve("store"));
        String url = http.base().resolve("missing.xml").toString();
        assertThatThrownBy(() -> UrlTools.download(url, cas))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(url)
                .hasMessageContaining("404");
    }
}
