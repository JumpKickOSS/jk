// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.FakeClock;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A tool archive (Node.js, Maven, Gradle, …) shows on the run's download board. */
class ToolInstallerDownloadsTest {

    private final FakeClock clock = new FakeClock();
    private final IoLedger io = new IoLedger(clock);
    private final AtomicReference<TransferBoard.Snapshot> midway = new AtomicReference<>();
    private byte[] zip = new byte[0];
    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        zip = mavenZip();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Half the body, then hold until the client has read some of it, move the clock past the
        // dwell and record the board, then the rest.
        server.createContext("/maven.zip", exchange -> {
            exchange.sendResponseHeaders(200, zip.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(zip, 0, zip.length / 2);
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
                out.write(zip, zip.length / 2, zip.length - zip.length / 2);
            }
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void the_archive_streams_onto_the_run_board(@TempDir Path dir) throws Exception {
        ToolInstaller installer = new ToolInstaller(new Http(), new ToolRegistry(dir.resolve("tools")));
        ToolDistribution dist = new ToolDistribution(
                BuildTool.MAVEN, "3.9.9", base.resolve("/maven.zip"), "zip", Hashing.sha256Hex(zip));

        SessionContext.where(Session.defaults().withIo(io), () -> installer.install(dist));

        assertThat(midway.get().rows()).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo(BuildTool.MAVEN.displayName() + " 3.9.9");
            assertThat(row.total()).isEqualTo(zip.length);
            assertThat(row.read()).isPositive().isLessThan(zip.length);
        });
        TransferBoard.Snapshot after = io.transfers().snapshot();
        assertThat(after.files()).isEqualTo(1);
        assertThat(after.bytes()).isEqualTo(zip.length);
        assertThat(after.inflight()).isZero();
    }

    /**
     * A Maven-shaped zip padded with an incompressible entry, so the body spans many reads and half
     * of it is a real midpoint.
     */
    private static byte[] mavenZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("apache-maven-3.9.9/bin/mvn"));
            zip.write("#!/bin/sh\necho mvn\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("apache-maven-3.9.9/lib/padding.bin"));
            byte[] noise = new byte[256 * 1024];
            new Random(42).nextBytes(noise);
            zip.write(noise);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
