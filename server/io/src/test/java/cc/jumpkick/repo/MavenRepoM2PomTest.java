// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.Coordinate;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A POM in {@code ~/.m2} that jk never verified is a candidate like a jar: a fork's {@code mvn
 * install} under the same coordinates must not become the repository's POM.
 */
class MavenRepoM2PomTest {

    private static final String REL = "com/example/widget/1.0/widget-1.0.pom";
    private static final byte[] REAL =
            "<project><artifactId>widget</artifactId></project>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FORK =
            "<project><artifactId>widget</artifactId><!--fork--></project>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path settingsDir;

    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        Path xml = settingsDir.resolve("settings.xml");
        Files.writeString(xml, """
                <settings>
                  <profiles><profile><id>p</id><repositories>
                    <repository><id>stub</id><url>%s</url></repository>
                  </repositories></profile></profiles>
                  <activeProfiles><activeProfile>p</activeProfile></activeProfiles>
                </settings>
                """.formatted(base));
        System.setProperty(MavenSettings.SETTINGS_PROPERTY, xml.toString());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        SessionContext.reset();
    }

    private void serve(String path, byte[] body) {
        server.createContext(path, ex -> {
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    @Test
    void a_forked_pom_in_the_local_repository_is_not_the_repositorys(@TempDir Path tmp) throws Exception {
        Path m2 = tmp.resolve("m2");
        System.setProperty("jk.m2.local", m2.toString());
        Files.createDirectories(m2.resolve(REL).getParent());
        Files.write(m2.resolve(REL), FORK);
        serve("/" + REL, REAL);
        serve("/" + REL + ".sha1", Hashing.hashHex("SHA-1", REAL).getBytes(StandardCharsets.UTF_8));
        MavenRepo repo = new MavenRepo("stub", base, new Http(), new Cas(tmp.resolve("store")));

        // Not served as the repository's: the fork is named, never read as Central's POM.
        assertThatThrownBy(() -> RepoGroup.of(repo).tryFetchPom(Coordinate.of("com.example", "widget", "1.0")))
                .hasMessageContaining(m2.resolve(REL).toString())
                .hasMessageContaining("jk did not write it");
    }
}
