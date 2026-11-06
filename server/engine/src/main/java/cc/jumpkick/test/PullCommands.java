// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The channel a pull-mode test worker takes its {@code RUN}/{@code DONE} commands from: a loopback
 * socket the worker connects to, never its stdin. A test JVM's stdin is closed at start, so test
 * code that reads {@code System.in}, and every child that inherits it ({@code jk jshell} or
 * {@code jk run} driven in-process, a REPL a test starts), reads end-of-input instead of waiting
 * on the parent's command pipe or eating a command meant for the runner.
 *
 * <p>The worker proves it is the process this channel was opened for by sending {@link #token}
 * as its first line; a connection that does not is dropped and the next one is taken.
 */
final class PullCommands implements PluginProcess.Conversation, AutoCloseable {

    /** How long the first command waits for the worker's connection; the worker connects before its first {@code ready}. */
    private static final int CONNECT_WAIT_MS = 60_000;

    private final ServerSocket listener;
    private final String token;
    private @Nullable Socket socket;
    private @Nullable BufferedWriter out;

    private PullCommands(ServerSocket listener, String token) {
        this.listener = listener;
        this.token = token;
    }

    /** A fresh channel on an ephemeral loopback port, for one worker. */
    static PullCommands open() throws IOException {
        ServerSocket listener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        return new PullCommands(listener, Hashing.hex(secret));
    }

    /** The runner arguments that point a worker here: {@code --commands=<host>:<port>} and {@code --token=<hex>}. */
    List<String> args() {
        InetAddress at = listener.getInetAddress();
        String host = at instanceof Inet6Address ? "[" + at.getHostAddress() + "]" : at.getHostAddress();
        return List.of("--commands=" + host + ":" + listener.getLocalPort(), "--token=" + token);
    }

    @Override
    public synchronized void send(String line) {
        try {
            BufferedWriter w = writer();
            w.write(line);
            w.write('\n');
            w.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("test worker command channel: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void closeInput() {
        close();
    }

    @Override
    public synchronized void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // Already closed: the worker is gone.
        }
        try {
            listener.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private BufferedWriter writer() throws IOException {
        if (out != null) return out;
        long deadline = Clock.SYSTEM.nanos() + CONNECT_WAIT_MS * 1_000_000L;
        while (true) {
            long left = (deadline - Clock.SYSTEM.nanos()) / 1_000_000L;
            if (left <= 0) throw new SocketTimeoutException("the test worker never connected");
            listener.setSoTimeout((int) left);
            Socket s = listener.accept();
            s.setSoTimeout((int) Math.max(1, left));
            String first;
            try {
                first = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))
                        .readLine();
            } catch (IOException e) {
                s.close();
                continue;
            }
            if (!token.equals(first)) {
                s.close();
                continue;
            }
            s.setSoTimeout(0);
            listener.close();
            socket = s;
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            return out;
        }
    }
}
