// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What the default home's engine says when 8910 stays taken: whose engine holds it, or that no engine does. */
class PortHolderTest {

    private final List<HttpServer> started = new ArrayList<>();

    @AfterEach
    void stopAll() {
        started.forEach(s -> s.stop(0));
    }

    private int answering(int status, String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        s.start();
        started.add(s);
        return s.getAddress().getPort();
    }

    @Test
    void a_jk_engine_of_another_home_is_named_by_its_home() throws Exception {
        int port = answering(200, "{\"id\":\"0be7a1e5c0de\",\"role\":\"host\",\"jkHome\":\"/scratch/.jk\",\"port\":1}");
        assertThat(PortHolder.describe(port, PortHolder.homeAt(port), "/Users/me/.jk"))
                .isEqualTo("port " + port + " is held by the jk engine of home /scratch/.jk; run jk engine stop with"
                        + " JK_HOME set to that home to free it");
        assertThat(PortHolder.describe(port, PortHolder.homeAt(port), "/scratch/.jk"))
                .isEqualTo("port " + port + " is held by another engine of this home");
    }

    @Test
    void anything_else_is_another_program() throws Exception {
        int notJk = answering(404, "not found");
        int noHome = answering(200, "{\"id\":\"0be7a1e5c0de\"}");
        int closed;
        try (Socket held = new Socket()) {
            held.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            closed = held.getLocalPort();
            for (int port : new int[] {notJk, noHome, closed}) {
                assertThat(PortHolder.describe(port, PortHolder.homeAt(port), "/Users/me/.jk"))
                        .as("port " + port)
                        .isEqualTo("port " + port + " is held by another program");
            }
        }
    }
}
