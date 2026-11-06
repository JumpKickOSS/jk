// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The worker's end of the engine's command channel: a loopback socket the worker connects to and
 * reads its commands from ({@code RUN …}, {@code DONE}), one per line. The engine starts every
 * worker with its stdin closed, so code a worker runs that reads {@code System.in}, and every child
 * that inherits it, reads end-of-input instead of the command stream.
 *
 * <p>The engine names the channel with two arguments, {@value #COMMANDS}{@code <host>:<port>} and
 * {@value #TOKEN}{@code <hex>}; the worker sends the token as its first line, which is how the
 * engine knows the connection is this worker's.
 */
public final class CommandChannel {

    /** The argument that names the channel's address, {@code <host>:<port>}; an IPv6 host is bracketed. */
    public static final String COMMANDS = "--commands=";

    /** The argument that carries the token the worker proves itself with. */
    public static final String TOKEN = "--token=";

    private CommandChannel() {}

    /**
     * Connect to the channel {@code args} name.
     *
     * @throws IllegalArgumentException when either channel argument is missing
     */
    public static BufferedReader connect(List<String> args) throws IOException {
        @Nullable String address = null;
        @Nullable String token = null;
        for (String a : args) {
            if (a.startsWith(COMMANDS)) address = a.substring(COMMANDS.length());
            else if (a.startsWith(TOKEN)) token = a.substring(TOKEN.length());
        }
        if (address == null || token == null) {
            throw new IllegalArgumentException(
                    "a served worker needs " + COMMANDS + "<host>:<port> and " + TOKEN + "<hex>");
        }
        return connect(address, token);
    }

    /** Connect to {@code address} ({@code <host>:<port>}), prove the connection with {@code token}, and read commands from it. */
    public static BufferedReader connect(String address, String token) throws IOException {
        int colon = address.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("not <host>:<port>: " + address);
        String host = address.substring(0, colon).replace("[", "").replace("]", "");
        int port = Integer.parseInt(address.substring(colon + 1));
        Socket socket = new Socket(InetAddress.getByName(host), port);
        try {
            Writer hello = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
            hello.write(token + "\n");
            hello.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }
}
