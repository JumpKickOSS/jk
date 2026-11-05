// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * An HTTP proxy on {@code 127.0.0.1:0} that writes down the host of every request it is handed and
 * refuses it with a 403: the request log of everything a jk command dialed off this machine.
 *
 * <p>Name it as the {@code [network] proxy} of the user's {@code config.toml}, or hand its {@link
 * #url()} to the code under test as the proxy it chose. Loopback targets go
 * direct (a proxy for {@code 127.0.0.1} is never meant), so the stubs a test stands up answer as
 * usual and this sees only what went past them: an https target as its {@code CONNECT host:port},
 * an http one by its absolute URI. Nothing is forwarded, so a request that went around the user's
 * mirror fails here by name instead of reaching the internet.
 *
 * <p>Register it with {@code @RegisterExtension} on an instance field; it binds before each test
 * and closes after it.
 */
public final class RecordingProxy implements BeforeEachCallback, AfterEachCallback {

    private final List<String> hosts = new CopyOnWriteArrayList<>();
    private final List<String> authorized = new CopyOnWriteArrayList<>();
    private volatile @Nullable String requiredAuthorization;
    private volatile @Nullable ServerSocket socket;

    /**
     * Challenge every request that does not carry {@code user:password} as Basic with a 407, the
     * way an authenticating corporate proxy does; one that does is written down in {@link
     * #authorized()} before it is refused like the rest.
     */
    public RecordingProxy requiringBasic(String user, String password) {
        requiredAuthorization =
                "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        ServerSocket bound = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        socket = bound;
        // A platform thread: it parks in accept() for the whole test.
        Thread accept = new Thread(() -> serve(bound), "recording-proxy");
        accept.setDaemon(true);
        accept.start();
    }

    @Override
    public void afterEach(ExtensionContext context) throws IOException {
        ServerSocket bound = socket;
        if (bound != null) bound.close();
        socket = null;
    }

    /** {@code http://127.0.0.1:<port>}: the value of a {@code [network] proxy} key. */
    public String url() {
        return "http://127.0.0.1:"
                + Objects.requireNonNull(socket, "RecordingProxy.url() before start")
                        .getLocalPort();
    }

    /** The host of every request in arrival order, lower-cased, without a port. */
    public List<String> hosts() {
        return Collections.unmodifiableList(hosts);
    }

    /** The hosts of the requests that carried the credential {@link #requiringBasic} names. */
    public List<String> authorized() {
        return Collections.unmodifiableList(authorized);
    }

    private void serve(ServerSocket bound) {
        while (!bound.isClosed()) {
            try (Socket client = bound.accept()) {
                client.setSoTimeout(5_000);
                Request request = read(new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1)));
                String required = requiredAuthorization;
                String answer = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                if (request != null) {
                    hosts.add(request.host());
                    if (required != null && !required.equals(request.authorization())) {
                        answer = "HTTP/1.1 407 Proxy Authentication Required\r\n"
                                + "Proxy-Authenticate: Basic realm=\"recording-proxy\"\r\n"
                                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                    } else if (required != null) {
                        authorized.add(request.host());
                    }
                }
                OutputStream out = client.getOutputStream();
                out.write(answer.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (IOException closedOrDropped) {
                // closed by afterEach, or a client that hung up: the next accept decides which
            }
        }
    }

    /** One request's head: the host its request line names and its {@code Proxy-Authorization}. */
    record Request(String host, @Nullable String authorization) {}

    /**
     * The request's head: the host its request line names ({@code CONNECT host:port} or an absolute
     * URI) and its {@code Proxy-Authorization}.
     */
    private static @Nullable Request read(BufferedReader in) throws IOException {
        String line = in.readLine();
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) return null;
        // The rest of the head is read so the client sees the answer, not a reset.
        String authorization = null;
        for (String header = in.readLine(); header != null && !header.isEmpty(); header = in.readLine()) {
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).strip().equalsIgnoreCase("Proxy-Authorization")) {
                authorization = header.substring(colon + 1).strip();
            }
        }
        String target = parts[1];
        String host;
        if (parts[0].equalsIgnoreCase("CONNECT")) {
            int colon = target.lastIndexOf(':');
            host = colon > 0 ? target.substring(0, colon) : target;
        } else {
            host = URI.create(target).getHost();
        }
        return host == null ? null : new Request(host.toLowerCase(Locale.ROOT), authorization);
    }
}
