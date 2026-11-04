// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.build.RepositoryRoute;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fork's resolver reaches a repository through the proxy jk chose for it, the way Maven's and
 * Gradle's Quarkus plugins reach one through their build tool's proxy. The repository's host does
 * not resolve; only the proxy, standing in for a corporate one, can serve it, and it asks for its
 * credential before it does.
 */
class RouteProxyTest {

    private static final String REPO = "http://repo.jk-proxy-test.invalid/maven2";
    private static final String JAR_PATH = "/maven2/com/example/widget/1.0/widget-1.0.jar";
    private static final byte[] JAR = "the widget jar".getBytes(StandardCharsets.UTF_8);
    private static final String CREDENTIAL =
            "Basic " + Base64.getEncoder().encodeToString("corp:p@ss".getBytes(StandardCharsets.UTF_8));

    private final List<String> seen = new CopyOnWriteArrayList<>();
    private @Nullable HttpServer proxy;
    private @Nullable String savedSettings;

    @BeforeEach
    void start(@TempDir Path tmp) throws Exception {
        // The developer's own settings.xml (a mirror, a proxy) must not decide this test.
        Path settings = tmp.resolve("settings.xml");
        Files.writeString(settings, "<settings/>");
        savedSettings = System.getProperty("maven.settings");
        System.setProperty("maven.settings", settings.toString());

        Map<String, byte[]> files = Map.of(JAR_PATH, JAR, JAR_PATH + ".sha1", sha1(JAR));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> serve(exchange, files));
        server.start();
        proxy = server;
    }

    @AfterEach
    void stop() {
        if (proxy != null) proxy.stop(0);
        if (savedSettings == null) System.clearProperty("maven.settings");
        else System.setProperty("maven.settings", savedSettings);
    }

    @Test
    void a_routed_proxy_carries_the_resolvers_requests_and_its_credential(@TempDir Path tmp) throws Exception {
        String proxyUrl = "http://corp:p%40ss@127.0.0.1:" + port();
        RepositoryRoutes routes =
                RepositoryRoutes.of(List.of(new RepositoryRoutes.Route("corp-nexus", REPO, null, null, proxyUrl)));

        MavenArtifactResolver mvn = QuarkusAugmentMain.resolver(tmp.resolve("local"), null, false, routes);
        Path jar = mvn.resolve(new DefaultArtifact("com.example:widget:jar:1.0"))
                .getArtifact()
                .getFile()
                .toPath();

        assertThat(Files.readAllBytes(jar)).isEqualTo(JAR);
        assertThat(seen).contains("GET " + REPO.replace("/maven2", "") + JAR_PATH + " " + CREDENTIAL);
        assertThat(routes.describe()).contains("via proxy 127.0.0.1:" + port()).doesNotContain("p@ss", "p%40ss");
    }

    @Test
    void without_the_route_proxy_the_repository_is_out_of_reach(@TempDir Path tmp) throws Exception {
        RepositoryRoutes routes =
                RepositoryRoutes.of(List.of(new RepositoryRoutes.Route("corp-nexus", REPO, null, null)));

        MavenArtifactResolver mvn = QuarkusAugmentMain.resolver(tmp.resolve("local"), null, false, routes);

        assertThatThrownBy(() -> mvn.resolve(new DefaultArtifact("com.example:widget:jar:1.0")))
                .as("what the fork did before routes carried a proxy");
        assertThat(seen).isEmpty();
    }

    @Test
    void a_proxy_survives_the_route_file_the_fork_reads(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("routes.jsonl");
        RepositoryRoutes.write(
                List.of(RepositoryRoute.anonymous("corp-nexus", URI.create(REPO))
                        .through(URI.create("http://corp:p%40ss@127.0.0.1:3128"))),
                file);

        assertThat(RepositoryRoutes.read(file).routes())
                .singleElement()
                .extracting(RepositoryRoutes.Route::proxy)
                .isEqualTo("http://corp:p%40ss@127.0.0.1:3128");
    }

    /** Serve a proxied request for the repository's host; ask for the credential first, as a corporate proxy does. */
    private void serve(HttpExchange exchange, Map<String, byte[]> files) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
        if (auth == null) {
            exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"corp\"");
            exchange.sendResponseHeaders(407, -1);
            exchange.close();
            return;
        }
        String target = exchange.getRequestURI().toString();
        seen.add(exchange.getRequestMethod() + " " + target + " " + auth);
        byte[] body = target.startsWith("http://repo.jk-proxy-test.invalid/") && CREDENTIAL.equals(auth)
                ? files.get(exchange.getRequestURI().getPath())
                : null;
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private int port() {
        return Objects.requireNonNull(proxy).getAddress().getPort();
    }

    private static byte[] sha1(byte[] bytes) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
                .getBytes(StandardCharsets.US_ASCII);
    }
}
