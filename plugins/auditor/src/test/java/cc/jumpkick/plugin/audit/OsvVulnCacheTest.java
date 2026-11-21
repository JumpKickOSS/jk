// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An advisory's body is fetched once per {@code modified} time the batch reports for it: a second
 * audit answers from the cache, and one after OSV changed the advisory fetches it again.
 */
class OsvVulnCacheTest {

    private HttpServer server;
    private URI base;
    private volatile String modified = "2026-01-01T00:00:00Z";
    private volatile String severity = "HIGH";
    private final List<String> gets = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            if ("GET".equals(exchange.getRequestMethod())) {
                gets.add(exchange.getRequestURI().getPath());
                body = "{\"id\":\"GHSA-c\",\"summary\":\"s\",\"database_specific\":{\"severity\":\"" + severity
                        + "\"}}";
            } else {
                exchange.getRequestBody().readAllBytes();
                body = "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-c\",\"modified\":\"" + modified + "\"}]}]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
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
    void an_unmodified_advisory_is_read_from_the_cache(@TempDir Path cache) throws Exception {
        assertThat(audit(cache).getFirst().severity()).isEqualTo(AuditReport.Severity.HIGH);
        severity = "LOW"; // what a fetch would now see

        assertThat(audit(cache).getFirst().severity())
                .as("the cached body, not a new fetch")
                .isEqualTo(AuditReport.Severity.HIGH);
        assertThat(gets).hasSize(1);
    }

    @Test
    void a_modified_advisory_is_fetched_again(@TempDir Path cache) throws Exception {
        audit(cache);
        modified = "2026-02-01T00:00:00Z";
        severity = "LOW";

        assertThat(audit(cache).getFirst().severity()).isEqualTo(AuditReport.Severity.LOW);
        assertThat(gets).hasSize(2);
        audit(cache);
        assertThat(gets).as("the new body is the cached one").hasSize(2);
    }

    @Test
    void an_unreadable_entry_is_fetched_again(@TempDir Path cache) throws Exception {
        audit(cache);
        Files.writeString(cache.resolve("GHSA-c.json"), modified + "\n{not json");

        assertThat(audit(cache).getFirst().severity()).isEqualTo(AuditReport.Severity.HIGH);
        assertThat(gets).hasSize(2);
    }

    @Test
    void without_a_modified_time_nothing_is_cached(@TempDir Path cache) throws Exception {
        modified = "";
        audit(cache);
        audit(cache);

        assertThat(gets).hasSize(2);
        try (var files = Files.list(cache)) {
            assertThat(files).isEmpty();
        }
    }

    private List<AuditReport.Finding> audit(Path cache) throws Exception {
        OsvClient client = new OsvClient(base.resolve("/v1/querybatch"), base.resolve("/v1/vulns/"), new Http(), cache);
        Lockfile lock = new Lockfile(
                5,
                "jk test",
                "pubgrub-v1",
                List.of(new Lockfile.Artifact(
                        "g:a:jar:", "1.0", "central+https://...", "sha256:abc", null, List.of(Scope.MAIN), List.of())));
        return new OsvAuditor(client).audit(lock).findings();
    }
}
