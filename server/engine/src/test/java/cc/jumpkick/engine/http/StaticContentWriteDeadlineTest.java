// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHttpConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A static GET to a client that stops reading ends at the write deadline: the handler's write fails
 * and the exchange closes, instead of holding the handler thread for as long as the client stays.
 */
class StaticContentWriteDeadlineTest {

    @Test
    @Timeout(60)
    void a_client_that_stops_reading_is_cut_off_at_the_write_deadline(@TempDir Path web) throws Exception {
        // Far more than the loopback socket buffers hold, so the writes block once they are full.
        Files.write(web.resolve("big.bin"), new byte[64 * 1024 * 1024]);
        StaticContent content = new StaticContent(new JkHttpConfig.WebDisk(web, false), "test", Duration.ofMillis(500));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            try (exchange) {
                content.serve(exchange);
            } catch (IOException | RuntimeException e) {
                failure.set(e);
            } finally {
                handled.countDown();
            }
        });
        server.start();
        try (Socket client = new Socket()) {
            client.setReceiveBufferSize(4096);
            client.connect(server.getAddress());
            OutputStream request = client.getOutputStream();
            request.write("GET /big.bin HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            request.flush();

            assertThat(handled.await(30, TimeUnit.SECONDS))
                    .as("the handler returned while the client still held the connection")
                    .isTrue();
        } finally {
            server.stop(0);
            handlers.shutdownNow();
        }
        assertThat(failure.get()).isInstanceOf(IOException.class).hasMessageContaining("took no bytes");
    }
}
