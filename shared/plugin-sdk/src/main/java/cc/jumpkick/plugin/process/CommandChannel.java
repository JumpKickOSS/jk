// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
        String[] named = named(args);
        return connect(named[0], named[1]);
    }

    /**
     * Connect to the channel {@code args} name and keep both directions: commands come in on {@link
     * Duplex#in()}, and the worker's own answers go back on {@link Duplex#send}, never on its stdout.
     * A worker that answers here leaves stdout and stderr to whatever the code it runs prints.
     *
     * @throws IllegalArgumentException when either channel argument is missing
     */
    public static Duplex connectDuplex(List<String> args) throws IOException {
        String[] named = named(args);
        Socket socket = open(named[0], named[1]);
        try {
            return new Duplex(socket);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** The address and the token {@code args} name, in that order. */
    private static String[] named(List<String> args) {
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
        return new String[] {address, token};
    }

    /** Connect to {@code address} ({@code <host>:<port>}), prove the connection with {@code token}, and read commands from it. */
    public static BufferedReader connect(String address, String token) throws IOException {
        Socket socket = open(address, token);
        try {
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** Connect to {@code address} and send {@code token} as the first line. */
    private static Socket open(String address, String token) throws IOException {
        int colon = address.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("not <host>:<port>: " + address);
        String host = address.substring(0, colon).replace("[", "").replace("]", "");
        int port = Integer.parseInt(address.substring(colon + 1));
        Socket socket = new Socket(InetAddress.getByName(host), port);
        try {
            Writer hello = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
            hello.write(token + "\n");
            hello.flush();
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Both directions of a connected channel. Lines sent are whole: {@link #send} holds a lock across
     * the write and the flush, so answers from many threads never interleave inside a line.
     */
    public static final class Duplex implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final Writer out;

        Duplex(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        /** The engine's commands, one per line; end of input when the engine closes the channel. */
        public BufferedReader in() {
            return in;
        }

        /**
         * Send one line to the engine. A channel the engine has closed swallows the line: the engine
         * is gone or has stopped listening, and the worker learns that from its input ending.
         */
        public void send(String line) {
            synchronized (out) {
                try {
                    out.write(line);
                    out.write('\n');
                    out.flush();
                } catch (IOException ignored) {
                    // The engine closed its end; reading the next command says so.
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
