// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A plain response is bounded the way a stream is: a client that stops reading is cut off and the
 * handler returns, while a client that reads slowly gets the whole body, since a large body goes out
 * in slices that each have the whole deadline.
 */
class PlainResponseWriteDeadlineTest {

    private static final Duration LIMIT = Duration.ofMillis(300);

    @Test
    @Timeout(30)
    void a_plain_response_whose_client_stops_reading_ends() throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.createContext("/big", exchange -> {
            try (exchange) {
                HttpResponses.sendBytes(exchange, 200, "application/octet-stream", new byte[16 << 20], LIMIT);
            } catch (IOException cutOff) {
                // what the test waits for
            } finally {
                ended.countDown();
            }
        });
        server.start();
        try (Socket client = new Socket()) {
            client.setReceiveBufferSize(4096);
            client.connect(server.getAddress());
            OutputStream request = client.getOutputStream();
            request.write("GET /big HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            request.flush();
            assertThat(ended.await(10, TimeUnit.SECONDS))
                    .as("the response ended while the client still held the connection")
                    .isTrue();
        } finally {
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    @Test
    void a_large_write_goes_out_in_slices_each_within_the_deadline() throws IOException {
        // Takes 100 ms per 64 KiB: the whole 512 KiB in one write would take 0.8 s, past the limit.
        AtomicInteger largest = new AtomicInteger();
        OutputStream slow = new OutputStream() {
            @Override
            public void write(int b) {}

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                largest.accumulateAndGet(len, Math::max);
                pause(100L * len / (64 * 1024));
            }
        };
        try (OutputStream out = new WriteDeadline(slow, LIMIT)) {
            out.write(new byte[512 << 10]);
        }
        assertThat(largest.get()).isEqualTo(WriteDeadline.SLICE);
    }

    @Test
    @Timeout(30)
    void closing_is_bounded_too() {
        OutputStream stuck = new OutputStream() {
            @Override
            public void write(int b) {}

            @Override
            public void close() throws IOException {
                pause(TimeUnit.MINUTES.toMillis(5));
            }
        };
        OutputStream out = new WriteDeadline(stuck, LIMIT);
        assertThatThrownBy(out::close).isInstanceOf(IOException.class).hasMessageContaining("took no bytes for 300 ms");
    }

    private static void pause(long millis) throws InterruptedIOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new InterruptedIOException("interrupted");
        }
    }
}
