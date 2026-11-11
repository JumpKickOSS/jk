// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.process;

import cc.jumpkick.host.ProcessEnv;
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
 * <p>The engine names the channel with the argument {@value #COMMANDS}{@code <host>:<port>} and
 * hands the worker a token in {@value #TOKEN_ENV}, never on its command line, which every user of
 * the host can read; the worker sends the token as its first line, which is how the engine knows
 * the connection is this worker's.
 */
public final class CommandChannel {

    /** The argument that names the channel's address, {@code <host>:<port>}; an IPv6 host is bracketed. */
    public static final String COMMANDS = "--commands=";

    /** The environment variable that carries the token the worker proves itself with. */
    public static final String TOKEN_ENV = "JK_COMMAND_TOKEN";

    private CommandChannel() {}

    /**
     * Connect to the channel {@code args} name, proving it with this process's token.
     *
     * @throws IllegalArgumentException when the channel argument or the token is missing
     */
    public static BufferedReader connect(List<String> args) throws IOException {
        return connect(args, token());
    }

    /** As {@link #connect(List)} with {@code token} for the process's own. */
    public static BufferedReader connect(List<String> args, @Nullable String token) throws IOException {
        return connect(address(args), required(token));
    }

    /**
     * Connect to the channel {@code args} name and keep both directions: commands come in on {@link
     * Duplex#in()}, and the worker's own answers go back on {@link Duplex#send}, never on its stdout.
     * A worker that answers here leaves stdout and stderr to whatever the code it runs prints.
     *
     * @throws IllegalArgumentException when the channel argument or the token is missing
     */
    public static Duplex connectDuplex(List<String> args) throws IOException {
        Socket socket = open(address(args), required(token()));
        try {
            return new Duplex(socket);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** The token the engine started this process with, or null when it was started without a channel. */
    public static @Nullable String token() {
        return ProcessEnv.get(TOKEN_ENV);
    }

    /** The address {@code args} name. */
    private static String address(List<String> args) {
        for (String a : args) {
            if (a.startsWith(COMMANDS)) return a.substring(COMMANDS.length());
        }
        throw new IllegalArgumentException("a served worker needs " + COMMANDS + "<host>:<port>");
    }

    private static String required(@Nullable String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("a served worker needs its token in " + TOKEN_ENV);
        }
        return token;
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
