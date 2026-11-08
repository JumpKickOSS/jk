// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import org.junit.jupiter.api.Test;

/**
 * A dashboard page that goes away takes its event stream's slot with it: reloads in a row against
 * one warm engine, each new page's stream admitted at once while the engine lets go of the last.
 * The page side closes its stream on {@code pagehide}; this is the engine side, which learns of
 * the close only through the heartbeat write that fails.
 */
class HttpEventStreamReleaseTest extends HttpEngineServerHarness {

    /** Generous against the heartbeat: a missed release shows as a stream held for its life. */
    private static final long RELEASE_BOUND_MILLIS = 2_500;

    @Test
    void reloading_the_page_never_waits_on_the_previous_pages_stream() throws Exception {
        server.sse().heartbeatMillis(50);
        // Room for exactly one page and the page reloading after it: a stream the engine kept
        // past its page would leave the next reload a 503 instead of a stream.
        int held = server.admission().webSse().availablePermits() - 2;
        server.admission().webSse().acquire(held);
        try {
            Socket page = openPage();
            for (int reload = 0; reload < 10; reload++) {
                page.close();
                page = openPage();
                assertThat(millisUntilStreams(held + 1))
                        .as("reload %d: the previous page's stream is let go", reload)
                        .isLessThan(RELEASE_BOUND_MILLIS);
            }
            page.close();
        } finally {
            server.admission().webSse().release(held);
        }
    }

    @Test
    void a_closed_page_frees_its_stream_on_the_default_heartbeat() throws Exception {
        assertThat(SseEndpoint.DEFAULT_HEARTBEAT_MILLIS).isLessThanOrEqualTo(1_000);
        Socket page = openPage();
        assertThat(server.liveEventStreams()).isEqualTo(1);
        page.close();
        assertThat(millisUntilStreams(0)).isLessThan(RELEASE_BOUND_MILLIS);
    }

    /** One page's {@code EventSource}: a raw connection, so the test closes it the way a tab does. */
    private Socket openPage() throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        String request = "GET /api/events HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Authorization: Bearer " + token() + "\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(UTF_8));
        socket.setSoTimeout(5_000);
        StringBuilder seen = new StringBuilder();
        InputStream in = socket.getInputStream();
        while (!seen.toString().contains(": connected")) {
            int b = in.read();
            if (b < 0) throw new AssertionError("stream ended before it connected: " + seen);
            seen.append((char) b);
        }
        assertThat(seen.toString()).startsWith("HTTP/1.1 200");
        return socket;
    }

    /** How long until exactly {@code n} streams hold slots, or the bound if they never do. */
    private long millisUntilStreams(int n) throws InterruptedException {
        long start = System.nanoTime();
        while (server.liveEventStreams() != n) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (elapsed >= RELEASE_BOUND_MILLIS) return elapsed;
            Thread.sleep(10);
        }
        return (System.nanoTime() - start) / 1_000_000;
    }
}
