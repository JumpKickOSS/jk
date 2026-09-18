// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step-dependency at a URL is fetched once into the store and served from there after: the
 * second build asks the server nothing, and an offline build with nothing cached fails naming the
 * URL rather than reaching out.
 */
class UrlToolsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @Test
    void a_url_is_fetched_once_and_read_from_the_store_after(@TempDir Path tmp) throws Exception {
        List<String> requests = new ArrayList<>();
        http.beforeServe(requests::add).serve("/build/rules.xml", "<module name=\"Checker\"/>");
        Cas cas = new Cas(tmp.resolve("store"));
        String url = http.base().resolve("build/rules.xml").toString();

        Path first = UrlTools.fetch(url, cas);
        Path second = UrlTools.fetch(url, cas);

        assertThat(first).isEqualTo(second).isRegularFile().hasFileName("rules.xml");
        assertThat(first).startsWith(cas.root().resolve("tools").resolve("url"));
        assertThat(Files.readString(first)).isEqualTo("<module name=\"Checker\"/>");
        assertThat(requests).hasSize(1);
    }

    @Test
    void offline_with_nothing_cached_fails_naming_the_url(@TempDir Path tmp) throws Exception {
        http.serve("/build/rules.xml", "<module name=\"Checker\"/>");
        Cas cas = new Cas(tmp.resolve("store"));
        String url = http.base().resolve("build/rules.xml").toString();
        Session offline = Session.defaults().withConfig(JkConfig.empty().withOffline(true));

        assertThatThrownBy(() -> SessionContext.where(offline, () -> UrlTools.fetch(url, cas)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(url)
                .hasMessageContaining("--offline");
        assertThat(http.served()).containsKey("/build/rules.xml");

        Path fetched = UrlTools.fetch(url, cas);
        assertThat(SessionContext.where(offline, () -> UrlTools.fetch(url, cas)))
                .as("a cached file is read offline")
                .isEqualTo(fetched);
    }

    @Test
    void a_status_other_than_200_is_a_failure_naming_it(@TempDir Path tmp) {
        Cas cas = new Cas(tmp.resolve("store"));
        String url = http.base().resolve("missing.xml").toString();
        assertThatThrownBy(() -> UrlTools.fetch(url, cas))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(url)
                .hasMessageContaining("404");
    }
}
