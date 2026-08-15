// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Platform BOM selectors resolve to a concrete catalog version before management load. */
class PlatformBomVersionsTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void exact_pin_returns_literal_without_needing_metadata(@TempDir Path tmp) throws Exception {
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", base, new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parseFloating("=1.2.3"));
        assertThat(v).isEqualTo("1.2.3");
    }

    @Test
    void caret_major_floor_picks_highest_stable(@TempDir Path tmp) throws Exception {
        serveMetadata("org.example", "bom", List.of("4.0.0", "4.0.1", "4.1.0", "4.1.0-RC1", "5.0.0"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", base, new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parseFloating("4"));
        assertThat(v).isEqualTo("4.1.0");
    }

    @Test
    void caret_floor_at_minor_does_not_go_below_anchor(@TempDir Path tmp) throws Exception {
        serveMetadata("org.example", "bom", List.of("4.0.0", "4.0.1", "4.1.0", "4.2.0"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", base, new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parseFloating("4.1.0"));
        assertThat(v).isEqualTo("4.2.0");
    }

    @Test
    void tilde_stays_within_minor(@TempDir Path tmp) throws Exception {
        serveMetadata("org.example", "bom", List.of("4.1.0", "4.1.5", "4.2.0"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", base, new Http(), new Cas(tmp.resolve("c"))));
        String v = PlatformBomVersions.resolve(repos, "org.example", "bom", VersionSelector.parseFloating("~4.1.0"));
        assertThat(v).isEqualTo("4.1.5");
    }

    @Test
    void latest_is_rejected(@TempDir Path tmp) {
        RepoGroup repos = RepoGroup.of(new MavenRepo("local", base, new Http(), new Cas(tmp.resolve("c"))));
        assertThatThrownBy(() -> PlatformBomVersions.resolve(
                        repos, "org.example", "bom", VersionSelector.parseFloating("latest")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact or caret/tilde");
    }

    private void serveMetadata(String group, String artifact, List<String> versions) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
        StringBuilder body = new StringBuilder();
        body.append("<metadata><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) body.append("<version>").append(v).append("</version>");
        body.append("</versions></versioning></metadata>");
        served.put(path, body.toString().getBytes(StandardCharsets.UTF_8));
    }
}
