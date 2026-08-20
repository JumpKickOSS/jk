// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.PomRuntimeClasspath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A thin worker fetched from the official repo provisions its runtime classpath from the
 * published Maven POM — jar + pom only; no {@code .deps} closure file.
 */
class PluginJarDepsFetchTest {

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> served = new HashMap<>();
    /** Restored after each test — self-host / Gradle may pin a real publisher jar. */
    private String savedPublisherJarProp;

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
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        System.setProperty(PluginJar.OFFICIAL_REPO_URL_PROPERTY, base);
        savedPublisherJarProp = System.getProperty(PluginJar.PUBLISHER.jarProperty());
        System.clearProperty(PluginJar.PUBLISHER.jarProperty());
    }

    @AfterEach
    void stop() {
        System.clearProperty(PluginJar.OFFICIAL_REPO_URL_PROPERTY);
        if (savedPublisherJarProp != null) {
            System.setProperty(PluginJar.PUBLISHER.jarProperty(), savedPublisherJarProp);
        } else {
            System.clearProperty(PluginJar.PUBLISHER.jarProperty());
        }
        server.stop(0);
    }

    @Test
    void official_fetch_walks_the_pom_and_pulls_maven_deps(@TempDir Path tmp) throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        String pomRel = rel.substring(0, rel.length() - 4) + ".pom";
        String ver = JkVersion.VERSION;
        serve("/" + rel, "thin-worker-jar");
        serve("/" + pomRel, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-publisher</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(ver));
        serve("/com/foo/lib/1.0/lib-1.0.jar", "dep-bytes");
        serve("/com/foo/lib/1.0/lib-1.0.pom", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.foo</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);

        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache")));

        assertThat(Files.readString(jar)).isEqualTo("thin-worker-jar");
        assertThat(Files.exists(Path.of(jar + ".deps"))).isFalse();
        assertThat(Files.exists(Path.of(jar + ".classpath"))).isFalse();
        Path pom = jar.resolveSibling(jar.getFileName().toString().replace(".jar", ".pom"));
        assertThat(pom).exists();
        List<Path> cp = PomRuntimeClasspath.resolve(jar);
        Path dep = tmp.resolve("cache/repos/jumpkick/com/foo/lib/1.0/lib-1.0.jar");
        assertThat(cp).contains(dep.toAbsolutePath().normalize());
        assertThat(Files.readString(dep)).isEqualTo("dep-bytes");
    }

    @Test
    void official_fetch_without_a_pom_fails(@TempDir Path tmp) {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel, "fat-worker-jar");

        assertThatThrownBy(() -> PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache"))))
                .isInstanceOf(PluginJarNotFoundException.class)
                .hasMessageContaining("POM");
    }

    private void serve(String path, String body) {
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
