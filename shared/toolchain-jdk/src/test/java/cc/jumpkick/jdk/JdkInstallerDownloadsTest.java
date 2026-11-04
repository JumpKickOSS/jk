// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.FakeClock;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A JDK archive shows on the run's download board, named as the JDK progress label names it. */
class JdkInstallerDownloadsTest {

    private final FakeClock clock = new FakeClock();
    private final IoLedger io = new IoLedger(clock);
    private final byte[] archive = new byte[200_000];
    private final AtomicReference<TransferBoard.Snapshot> midway = new AtomicReference<>();
    private HttpServer server;
    private URI base;
    private String prevStateDir;
    private Path isolatedState;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Half the body, then hold until the client has read some of it, move the clock past
        // the dwell and record the board, then the rest.
        server.createContext("/jdk.zip", exchange -> {
            exchange.sendResponseHeaders(200, archive.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(archive, 0, archive.length / 2);
                out.flush();
                try {
                    Await.until(
                            Duration.ofSeconds(10),
                            () -> io.transfers().snapshot().bytes() > 0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                clock.advance(Duration.ofMillis(300));
                midway.set(io.transfers().snapshot());
                out.write(archive, archive.length / 2, archive.length - archive.length / 2);
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        isolatedState = Files.createTempDirectory("jk-state-");
        prevStateDir = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", isolatedState.toString());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        if (prevStateDir == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevStateDir);
        PathUtil.deleteRecursively(isolatedState);
    }

    @Test
    void the_archive_streams_onto_the_run_board(@TempDir Path dir) throws Exception {
        JdkCatalog.Entry entry = new JdkCatalog.Entry(
                "eclipse",
                "Temurin",
                "temurin-21",
                21,
                "21.0.5",
                true,
                false,
                List.of(),
                "linux",
                "x64",
                "zip",
                base.resolve("/jdk.zip"),
                Hashing.sha256Hex(archive),
                archive.length,
                "21.0.5-tem-x64-linux",
                "");
        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(dir.resolve("jdks")));

        SessionContext.where(Session.defaults().withIo(io), () -> installer.download(entry, read -> {}));

        assertThat(midway.get().rows()).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo(JdkProgressLabel.compactName(entry));
            assertThat(row.total()).isEqualTo(archive.length);
            assertThat(row.read()).isPositive().isLessThan(archive.length);
        });
        TransferBoard.Snapshot after = io.transfers().snapshot();
        assertThat(after.files()).isEqualTo(1);
        assertThat(after.bytes()).isEqualTo(archive.length);
        assertThat(after.inflight()).isZero();
    }
}
