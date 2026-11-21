// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A request body is read under a deadline: a client that sends its headers and part of its body and
 * then stalls is cut off, and the handler lets go of the permit it holds, while a client that sends
 * its whole body in time is read as before.
 */
class RequestBodyDeadlineTest {

    private static final Duration LIMIT = Duration.ofMillis(300);

    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stopHandlers() {
        handlers.shutdownNow();
    }

    @Test
    @Timeout(30)
    void a_client_that_stalls_mid_body_is_cut_off_and_its_permit_released() throws Exception {
        Semaphore permit = new Semaphore(1);
        CompletableFuture<Throwable> ended = new CompletableFuture<>();
        HttpServer server = server(permit, ended);
        try (Socket client = new Socket()) {
            client.connect(server.getAddress());
            OutputStream request = client.getOutputStream();
            request.write(("POST /body HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 100\r\n\r\n{\"partial\":")
                    .getBytes(StandardCharsets.US_ASCII));
            request.flush();
            Throwable failure = ended.get(10, TimeUnit.SECONDS);
            assertThat(failure)
                    .isInstanceOf(IOException.class)
                    .hasMessage("the client did not send its body within 300 ms; closed the request");
            assertThat(permit.availablePermits()).isEqualTo(1);
            client.setSoTimeout(10_000);
            InputStream answer = client.getInputStream();
            assertThat(answer.read()).as("the connection is closed").isEqualTo(-1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(30)
    void a_whole_body_in_time_is_read() throws Exception {
        Semaphore permit = new Semaphore(1);
        CompletableFuture<Throwable> ended = new CompletableFuture<>();
        HttpServer server = server(permit, ended);
        try (Socket client = new Socket()) {
            client.connect(server.getAddress());
            OutputStream request = client.getOutputStream();
            request.write(("POST /body HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 4\r\n\r\nbody")
                    .getBytes(StandardCharsets.US_ASCII));
            request.flush();
            assertThat(ended.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(permit.availablePermits()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    /** A server whose one handler holds {@code permit} while it reads the body, as admission does. */
    private HttpServer server(Semaphore permit, CompletableFuture<Throwable> ended) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/body", exchange -> {
            try (exchange) {
                permit.acquireUninterruptibly();
                try {
                    HttpRequests.bytes(exchange, HttpRequests.MAX_BODY_BYTES, LIMIT);
                } finally {
                    permit.release();
                }
                HttpResponses.sendText(exchange, 200, "read\n");
                ended.complete(null);
            } catch (IOException | RuntimeException e) {
                ended.complete(e);
            }
        });
        server.start();
        return server;
    }
}
