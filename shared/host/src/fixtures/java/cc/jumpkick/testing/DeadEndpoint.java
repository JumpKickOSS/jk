// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;

/**
 * An HTTP endpoint that is reachable and always fails at the transport: a loopback listener that
 * accepts every connection and closes it without reading a byte, so the client sees a reset or an
 * end of stream on its first request, in one attempt, on every OS.
 *
 * <p>Why this rather than a port nothing listens on: loopback port 1 refuses instantly
 * on a bare Linux or macOS host, but WSL2 and some VPN and container network stacks relay loopback
 * and let the connect hang until the client's 10 s connect timeout. Under jk's six-attempt retry
 * ladder that is a minute per fetch, and three suites lost six minutes of the fast tier to it. A
 * closed port models nothing a test needs — the failure under test is "the transport failed", and
 * an accepted-then-dropped connection is exactly that shape, deterministically.
 *
 * <pre>{@code
 * try (DeadEndpoint dead = DeadEndpoint.open()) {
 *     RepoGroup group = new RepoGroup(List.of(new MavenRepo("dead", dead.uri(), Http.failFast(), cas), good));
 *     ...
 * }
 * }</pre>
 *
 * <p>Pair it with {@code Http.failFast()} where the code under test lets the test choose the
 * client; production's ladder still adds ~3 s of backoff to a failure that is otherwise instant.
 */
public final class DeadEndpoint implements AutoCloseable {

    private final ServerSocket listener;
    private final Thread acceptor;

    private DeadEndpoint(ServerSocket listener) {
        this.listener = listener;
        this.acceptor = new Thread(this::dropEveryConnection, "dead-endpoint-" + listener.getLocalPort());
        this.acceptor.setDaemon(true);
    }

    /** Bind an ephemeral loopback port and start dropping connections. */
    public static DeadEndpoint open() throws IOException {
        var listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        var endpoint = new DeadEndpoint(listener);
        endpoint.acceptor.start();
        return endpoint;
    }

    /** {@code http://127.0.0.1:<port>/}. */
    public URI uri() {
        return uri("/");
    }

    /** {@code http://127.0.0.1:<port>} plus {@code path}, which must start with a slash. */
    public URI uri(String path) {
        return URI.create("http://" + listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort() + path);
    }

    private void dropEveryConnection() {
        while (!listener.isClosed()) {
            try (Socket accepted = listener.accept()) {
                // Reset rather than a graceful FIN: the peer's first read fails outright instead of
                // seeing a clean end of stream it might mistake for an empty response.
                accepted.setSoLinger(true, 0);
            } catch (IOException closed) {
                return;
            }
        }
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
            // A listener that will not close still stops accepting: the acceptor exits on the error.
        }
        acceptor.interrupt();
    }
}
