// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1657: a tool coordinate in a {@code jk-plugin.toml} is written by the plugin author, so a
 * bare version is an exact pin — only an explicit caret/tilde floats. This is the opposite of the
 * {@code jk.toml} dependency convention, and deliberately so: android's r8/aapt2/manifest-merger
 * literals track one AGP tools line and must not drift off it.
 */
class PluginToolCoordinateTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();
    private final AtomicInteger metadataRequests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/maven-metadata.xml")) metadataRequests.incrementAndGet();
            byte[] body = served.get(path);
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
    void a_bare_version_is_exact_and_costs_no_metadata_fetch(@TempDir Path tmp) throws Exception {
        serveMetadata("com.android.tools", "r8", List.of("8.5.35", "8.9.35", "8.13.19"));

        Coordinate coord = PluginBuild.resolveCoordinate(repos(tmp), "com.android.tools:r8:8.5.35");

        assertThat(coord.version()).isEqualTo("8.5.35");
        assertThat(metadataRequests)
                .describedAs("an exact pin must not consult maven-metadata.xml")
                .hasValue(0);
    }

    @Test
    void an_explicit_caret_floats_to_the_highest_stable_in_the_line(@TempDir Path tmp) throws Exception {
        serveMetadata("org.springframework.boot", "spring-boot-loader", List.of("4.0.0", "4.1.0", "4.2.0-RC1", "5.0.0"));

        Coordinate coord = PluginBuild.resolveCoordinate(repos(tmp), "org.springframework.boot:spring-boot-loader:^4");

        assertThat(coord.version()).isEqualTo("4.1.0");
        assertThat(metadataRequests).hasValue(1);
    }

    @Test
    void a_float_keeps_the_classifier_and_type(@TempDir Path tmp) throws Exception {
        serveMetadata("com.google.protobuf", "protoc", List.of("4.33.1", "4.34.0"));

        Coordinate coord = PluginBuild.resolveCoordinate(repos(tmp), "com.google.protobuf:protoc:^4.33.1:linux-x86_64@exe");

        assertThat(coord.version()).isEqualTo("4.34.0");
        assertThat(coord.classifier()).isEqualTo("linux-x86_64");
        assertThat(coord.type()).isEqualTo("exe");
    }

    @Test
    void a_bare_version_keeps_the_classifier_and_type(@TempDir Path tmp) throws Exception {
        Coordinate coord =
                PluginBuild.resolveCoordinate(repos(tmp), "com.android.tools.build:aapt2:8.7.3-12006047:linux");

        assertThat(coord.version()).isEqualTo("8.7.3-12006047");
        assertThat(coord.classifier()).isEqualTo("linux");
        assertThat(metadataRequests).hasValue(0);
    }

    @Test
    void an_equals_prefix_pins_exactly_and_is_stripped(@TempDir Path tmp) throws Exception {
        serveMetadata("io.micronaut.aot", "micronaut-aot-cli", List.of("3.1.0", "3.9.0"));

        Coordinate coord = PluginBuild.resolveCoordinate(repos(tmp), "io.micronaut.aot:micronaut-aot-cli:=3.1.0");

        assertThat(coord.version()).isEqualTo("3.1.0");
        assertThat(metadataRequests).hasValue(0);
    }

    @Test
    void packager_tool_versions_follow_the_same_rule(@TempDir Path tmp) throws Exception {
        serveMetadata("com.android.tools", "r8", List.of("8.13.19", "8.20.0"));
        RepoGroup repos = repos(tmp);

        assertThat(PluginBuild.resolveToolVersion(repos, "com.android.tools:r8", "8.13.19"))
                .isEqualTo("8.13.19");
        assertThat(metadataRequests).hasValue(0);

        assertThat(PluginBuild.resolveToolVersion(repos, "com.android.tools:r8", "^8.13.19"))
                .isEqualTo("8.20.0");
        assertThat(metadataRequests).hasValue(1);
    }

    private RepoGroup repos(Path tmp) {
        return RepoGroup.of(new MavenRepo("local", base, new Http(), new Cas(tmp.resolve("cas"))));
    }

    private void serveMetadata(String group, String artifact, List<String> versions) {
        StringBuilder xml = new StringBuilder("<metadata><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) xml.append("<version>").append(v).append("</version>");
        xml.append("</versions></versioning></metadata>");
        served.put(
                "/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml",
                xml.toString().getBytes(StandardCharsets.UTF_8));
    }
}
