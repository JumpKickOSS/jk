// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A connect-level fault names a dead address rather than a dropped request, and once one ladder
 * has ended in it the address is refused before any client dials it again.
 */
class ConnectFaultsTest {

    @BeforeEach
    @AfterEach
    void forget() {
        ConnectFaults.forget();
    }

    /**
     * The JDK's HTTP client reports a refused connect as a {@code ConnectException} with no message,
     * wrapped in another and a {@code ClosedChannelException}; a peer that accepted and dropped the
     * connection is the same class saying reset, and a reset is one request's failure, not a dead
     * address.
     */
    @Test
    void a_refused_connect_is_the_message_less_connect_exception_and_a_reset_is_not_one() {
        ConnectException bare = new ConnectException();
        bare.initCause(new ClosedChannelException());
        IOException refused = new IOException("GET failed after 6 attempts", bare);
        assertThat(ConnectFaults.describe(refused)).isEqualTo("ConnectException: the connection was not accepted");
        assertThat(ConnectFaults.describe(new ConnectException("Connection refused")))
                .isEqualTo("ConnectException: Connection refused");
        assertThat(ConnectFaults.describe(new ConnectException("Connection reset by peer (connect failed)")))
                .isNull();
        assertThat(ConnectFaults.describe(new IOException("HTTP 503"))).isNull();
    }

    @Test
    void an_address_that_answered_nothing_through_a_whole_ladder_is_refused_before_the_next_request_dials()
            throws Exception {
        try (RefusingPort refusing = RefusingPort.open()) {
            ladderThenRefused(refusing.port());
        }
    }

    private void ladderThenRefused(int port) throws Exception {
        URI closed = URI.create("http://127.0.0.1:" + port + "/maven2/a.pom");
        Http http = http();

        assertThatThrownBy(() -> http.get(closed))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("failed after 3 attempts")
                .hasCauseInstanceOf(ConnectException.class);
        assertThat(ConnectFaults.refusing(closed)).startsWith("ConnectException");

        // A different client, the same address: refused with the remembered fault as its cause,
        // which reads back as the connect-level fault it was.
        assertThatThrownBy(() -> http().get(closed.resolve("/maven2/b.pom")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("was not attempted")
                .hasCauseInstanceOf(ConnectFaults.Remembered.class)
                .satisfies(e -> assertThat(ConnectFaults.describe(e)).startsWith("ConnectException"));

        // Another port of the same host is another address.
        assertThat(ConnectFaults.refusing(URI.create("http://127.0.0.1:" + (port == 65535 ? 1 : port + 1) + "/")))
                .isNull();

        ConnectFaults.forget();
        assertThatThrownBy(() -> http().get(closed)).hasMessageContaining("failed after 3 attempts");
    }

    /** Through a proxy the proxy is what answers nothing; the target host is not blamed for it. */
    @Test
    void a_request_routed_through_a_proxy_that_answers_nothing_remembers_the_proxy_not_the_target() throws Exception {
        try (RefusingPort refusing = RefusingPort.open()) {
            proxyAnswersNothing(refusing.port());
        }
    }

    private void proxyAnswersNothing(int port) throws Exception {
        URI behind = URI.create("http://repo.example.test/maven2/a.pom");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", port)))
                .build();
        Http http = new Http(client, new Duration[] {Duration.ofMillis(1)});

        assertThatThrownBy(() -> http.get(behind))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("failed after");

        assertThat(ConnectFaults.refusing("127.0.0.1:" + port)).startsWith("ConnectException");
        assertThat(ConnectFaults.refusing(behind))
                .as("the proxy answered nothing; the host behind it was never dialled")
                .isNull();
        assertThatThrownBy(() -> http.get(behind.resolve("/maven2/b.pom")))
                .hasMessageContaining("was not attempted")
                .hasCauseInstanceOf(ConnectFaults.Remembered.class);
    }

    /** A local repository started after a failed command is reached by the next one; a remote outage is still remembered. */
    @Test
    void the_next_command_forgets_this_machines_dead_addresses_and_keeps_remote_ones() {
        ConnectFaults.noteRefusing("127.0.0.1:8081", "ConnectException: refused");
        ConnectFaults.noteRefusing("localhost:4873", "ConnectException: refused");
        ConnectFaults.noteRefusing("[::1]:8081", "ConnectException: refused");
        ConnectFaults.noteRefusing("repo.example.com:443", "UnknownHostException: repo.example.com");

        ConnectFaults.forgetLoopback();

        assertThat(ConnectFaults.refusing("127.0.0.1:8081")).isNull();
        assertThat(ConnectFaults.refusing("localhost:4873")).isNull();
        assertThat(ConnectFaults.refusing("[::1]:8081")).isNull();
        assertThat(ConnectFaults.refusing("repo.example.com:443")).startsWith("UnknownHostException");
    }

    @Test
    void the_authority_carries_the_schemes_default_port_when_the_url_names_none() {
        assertThat(ConnectFaults.authority(URI.create("https://Repo.Example/maven2/")))
                .isEqualTo("repo.example:443");
        assertThat(ConnectFaults.authority(URI.create("http://repo.example/maven2/")))
                .isEqualTo("repo.example:80");
        assertThat(ConnectFaults.authority(URI.create("http://repo.example:8081/x")))
                .isEqualTo("repo.example:8081");
    }

    /**
     * A loopback port that refuses connections while open: the local end of an established
     * connection, which no listener shares. A bound, never-listening channel is refused on Linux
     * but silently dropped on macOS, and a closed probe socket frees the port for any process.
     */
    private record RefusingPort(ServerSocketChannel server, SocketChannel held, SocketChannel accepted)
            implements AutoCloseable {

        static RefusingPort open() throws IOException {
            ServerSocketChannel server = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
            SocketChannel held = SocketChannel.open(server.getLocalAddress());
            return new RefusingPort(server, held, server.accept());
        }

        int port() throws IOException {
            return ((InetSocketAddress) held.getLocalAddress()).getPort();
        }

        @Override
        public void close() throws IOException {
            try (server;
                    held;
                    accepted) {}
        }
    }

    private static Http http() {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return new Http(client, new Duration[] {Duration.ofMillis(1), Duration.ofMillis(1)});
    }
}
