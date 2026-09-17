// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.MavenStub;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A loopback repository is whatever process holds its port right now — a test's stub, a proxy
 * under development — so the process-wide URL memos do not remember its answers: a stub that takes
 * over a sibling's ephemeral port is asked afresh, for the not-found memo and the version-list memo
 * alike.
 */
class LoopbackRepoMemoTest {

    private static final Coordinate LIB = Coordinate.of("com.example", "lib", "1.0");

    private @Nullable HttpServer server;

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        SessionContext.reset();
    }

    @AfterEach
    void stop() {
        HttpServer running = server;
        if (running != null) running.stop(0);
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    @Test
    void a_stub_that_takes_over_a_port_is_asked_for_what_its_predecessor_lacked(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> first = new ConcurrentHashMap<>();
        HttpServer predecessor = start(0, first);
        int port = predecessor.getAddress().getPort();
        MavenRepo repo = repo(tmp.resolve("first"), port);
        assertThatThrownBy(() -> repo.fetchPom(LIB)).isInstanceOf(MavenRepo.ArtifactNotFoundException.class);
        predecessor.stop(0);

        // The sibling test's stub, on the port the OS just handed back, holds the library.
        Map<String, byte[]> second = new ConcurrentHashMap<>();
        new MavenStub(second).leaf("com.example", "lib", "1.0");
        start(port, second);

        assertThat(repo(tmp.resolve("second"), port).fetchPom(LIB).url().getPort())
                .isEqualTo(port);
    }

    @Test
    void a_stub_that_takes_over_a_port_lists_its_own_versions(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> first = new ConcurrentHashMap<>();
        new MavenStub(first).metadata("com.example", "lib", "1.0");
        HttpServer predecessor = start(0, first);
        int port = predecessor.getAddress().getPort();
        assertThat(group(tmp.resolve("first"), port).availableVersions(LIB, Set.of(), false))
                .containsExactly("1.0");
        predecessor.stop(0);

        Map<String, byte[]> second = new ConcurrentHashMap<>();
        new MavenStub(second).metadata("com.example", "lib", "2.0");
        start(port, second);

        assertThat(group(tmp.resolve("second"), port).availableVersions(LIB, Set.of(), false))
                .containsExactly("2.0");
    }

    private HttpServer start(int port, Map<String, byte[]> served) throws IOException {
        HttpServer bound = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        bound.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        bound.start();
        server = bound;
        return bound;
    }

    /** A repository over {@code store}: one store per stub, so a catalog read is never a disk-cache hit from the other. */
    private static MavenRepo repo(Path store, int port) {
        return new MavenRepo(
                "stub",
                URI.create("http://127.0.0.1:" + port + "/"),
                new Http(),
                new Cas(store),
                RepoCredential.ANONYMOUS,
                false);
    }

    private static RepoGroup group(Path store, int port) {
        return RepoGroup.of(repo(store, port));
    }
}
