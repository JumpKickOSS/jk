// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * In-process HTTP stub of a Maven repository for the CLI command tests.
 *
 * <p>Register it with {@code @RegisterExtension} on an instance field: the server binds an
 * ephemeral 127.0.0.1 port before each test (so parallel suites never race over a port) and stops
 * after it. Responses come straight from the {@link #served()} map — a test describes a repository
 * by seeding paths, not by writing handlers — and unknown paths get a 404 so a missing fixture
 * entry fails fast as a resolution error instead of hanging.
 *
 * <p>This class exists because a dozen command tests used to carry byte-identical copies of the
 * server plumbing and the pom/metadata/jar registration helpers; keep additions here so they stay
 * shared.
 */
public final class MockMavenServer implements BeforeEachCallback, AfterEachCallback {

    private final Map<String, byte[]> served = new HashMap<>();
    private HttpServer server;
    private URI base;

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        start();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        stop();
    }

    /** Bind 127.0.0.1 on an ephemeral port and serve the {@link #served()} map. */
    public void start() throws IOException {
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

    /**
     * Idempotent so offline tests can kill the server mid-test (proving no network is needed) and
     * the extension's after-each teardown stays harmless.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Base URL of the running server, e.g. {@code http://127.0.0.1:<port>} (no trailing slash). */
    public URI base() {
        return base;
    }

    /**
     * Live path-to-body map backing the server. Mutable on purpose: tests put entries directly for
     * bespoke content (scripts, archives, classifier artifacts) and fixtures like
     * {@code DefaultTestDepsFixture.seed(...)} bulk-seed it.
     */
    public Map<String, byte[]> served() {
        return served;
    }

    /** Serve {@code body} as the pom of {@code group:artifact:version}. */
    public void registerPom(String group, String artifact, String version, String body) {
        served.put(mavenPath(group, artifact, version, "pom"), body.getBytes(StandardCharsets.UTF_8));
    }

    /** Serve {@code bytes} as the jar of {@code group:artifact:version}. */
    public void registerJar(String group, String artifact, String version, byte[] bytes) {
        served.put(mavenPath(group, artifact, version, "jar"), bytes);
    }

    /** Serve a compact {@code maven-metadata.xml} listing {@code versions} for the artifact. */
    public void registerMetadata(String group, String artifact, String... versions) {
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

    /** Serve a dependency-free pom (full XML prolog form) for {@code group:artifact:version}. */
    public void servePom(String group, String artifact, String version) {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
        served.put(mavenPath(group, artifact, version, "pom"), pom.getBytes());
    }

    /** Minimal pom body with no dependencies; pair with {@link #registerPom}. */
    public static String pom(String group, String artifact, String version) {
        return pom(group, artifact, version, "");
    }

    /** Minimal pom body whose {@code <dependencies>} element wraps {@code depBlock} verbatim. */
    public static String pom(String group, String artifact, String version, String depBlock) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(group, artifact, version, depBlock);
    }

    /** Repository path of the {@code ext} artifact of {@code group:artifact:version}. */
    public static String mavenPath(String group, String artifact, String version, String ext) {
        return "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + "."
                + ext;
    }
}
