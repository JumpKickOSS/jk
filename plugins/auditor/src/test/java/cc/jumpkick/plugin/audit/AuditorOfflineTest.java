// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An offline audit refuses instead of querying OSV.
 *
 * <p>An audit has no cached answer to fall back on, so degrading quietly would mean reporting
 * "no known vulnerabilities" without having asked — the same class of untrue safety claim
 * JK-2382 fixed. It refuses and names the endpoint instead.
 *
 * <p>{@code jk audit --offline} is also rejected client-side, before the engine hears about it.
 * That check does not cover the web/MCP trigger, and a guard in one client is not a property of
 * the product; this one is in the worker, where the request actually happens.
 */
class AuditorOfflineTest {

    private HttpServer server;
    private URI base;
    private final List<String> received = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(
                    exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"results\":[{}]}".getBytes(StandardCharsets.UTF_8));
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
    void an_offline_audit_asks_nothing_and_says_why(@TempDir Path dir) throws Exception {
        var out = capture();
        int exit = new Auditor().run(List.of(spec(dir, true).toString()), out.writer);

        assertThat(received)
                .describedAs("requests OSV received under --offline")
                .isEmpty();
        assertThat(exit).isEqualTo(1);
        assertThat(out.text())
                .contains("offline: refusing outbound request to")
                .contains(base.resolve("/v1/querybatch").toString());
    }

    /** The control: same spec, same endpoint, offline off — the query does happen. */
    @Test
    void the_same_audit_online_queries_osv(@TempDir Path dir) throws Exception {
        var out = capture();
        int exit = new Auditor().run(List.of(spec(dir, false).toString()), out.writer);

        assertThat(exit).isZero();
        assertThat(received).containsExactly("POST /v1/querybatch");
        assertThat(out.text()).doesNotContain("offline: refusing outbound request to");
    }

    private Path spec(Path dir, boolean offline) throws IOException {
        Path lock = dir.resolve("jk-lock.toml");
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "jk test",
                        "pubgrub-v1",
                        List.of(new Lockfile.Artifact(
                                "com.example:widget:jar:",
                                "1.0.0",
                                "central+https://repo.example/",
                                "sha256:abc",
                                null,
                                List.of(Scope.MAIN),
                                List.of()))),
                lock);

        Path spec = dir.resolve("audit-" + offline + ".spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_COMMAND, "audit", "jk-auditor")
                        .configString("lockfile", lock.toAbsolutePath().toString())
                        .configString("batchUrl", base.resolve("/v1/querybatch").toString())
                        .configString("vulnsUrl", base.resolve("/v1/vulns/").toString())
                        .offline(offline)
                        .lines(),
                StandardCharsets.UTF_8);
        return spec;
    }

    private record Capture(ByteArrayOutputStream buffer, ProtocolWriter writer) {
        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static Capture capture() {
        var buffer = new ByteArrayOutputStream();
        return new Capture(
                buffer, new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKAU:"));
    }
}
