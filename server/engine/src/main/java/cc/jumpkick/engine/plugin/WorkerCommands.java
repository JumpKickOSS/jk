// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.plugin.process.CommandChannel;
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
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The channel every worker takes its commands from ({@code RUN}, {@code DONE}, …): a loopback
 * socket the worker connects to, never its stdin. Workers start with their stdin closed, so code
 * they run that reads {@code System.in}, and every child that inherits it, reads end-of-input
 * instead of waiting on the engine's pipe or eating a command meant for the worker. The worker's
 * end is {@link CommandChannel}.
 *
 * <p>The worker proves it is the process this channel was opened for by sending the token as its
 * first line; a connection that does not is dropped and the next one is taken.
 */
public final class WorkerCommands implements PluginProcess.Conversation, AutoCloseable {

    /** How long a send waits for the worker's connection; a worker connects before it says it is ready. */
    public static final Duration CONNECT_WAIT = Duration.ofSeconds(60);

    /** How long a connection has to send its token before it is dropped. */
    private static final int HELLO_WAIT_MS = 10_000;

    private final ServerSocket listener;
    private final String token;
    private final Object connecting = new Object();
    private volatile @Nullable Socket socket;
    private volatile @Nullable BufferedWriter out;

    private WorkerCommands(ServerSocket listener, String token) {
        this.listener = listener;
        this.token = token;
    }

    /** A fresh channel on an ephemeral loopback port, for one worker. */
    public static WorkerCommands open() throws IOException {
        ServerSocket listener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        return new WorkerCommands(listener, Hashing.hex(secret));
    }

    /** The worker arguments that point it here: {@code --commands=<host>:<port>} and {@code --token=<hex>}. */
    public List<String> args() {
        InetAddress at = listener.getInetAddress();
        String host = at instanceof Inet6Address ? "[" + at.getHostAddress() + "]" : at.getHostAddress();
        return List.of(CommandChannel.COMMANDS + host + ":" + listener.getLocalPort(), CommandChannel.TOKEN + token);
    }

    /** Whether the worker has connected. */
    public boolean connected() {
        return out != null;
    }

    /**
     * Wait up to {@code wait} for the worker to connect. True once it has; false when it has not yet,
     * so a caller can look at the worker's life and its cancel probe between waits.
     *
     * @throws IOException when the channel is closed
     */
    public boolean connect(Duration wait) throws IOException {
        synchronized (connecting) {
            if (out != null) return true;
            long deadline = Clock.SYSTEM.nanos() + wait.toNanos();
            while (true) {
                long left = (deadline - Clock.SYSTEM.nanos()) / 1_000_000L;
                if (left <= 0) return false;
                listener.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
                Socket s;
                try {
                    s = listener.accept();
                } catch (SocketTimeoutException e) {
                    return false;
                }
                if (!proves(s)) {
                    s.close();
                    continue;
                }
                listener.close();
                socket = s;
                out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                return true;
            }
        }
    }

    private boolean proves(Socket s) {
        try {
            s.setSoTimeout(HELLO_WAIT_MS);
            String first =
                    new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            s.setSoTimeout(0);
            return token.equals(first);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Send one line, waiting up to {@link #CONNECT_WAIT} for the worker to connect first. A worker
     * that never connects fails the send; one that has gone since does not: its output ends, and the
     * reader of that output reports it.
     */
    @Override
    public void send(String line) {
        BufferedWriter w = out;
        if (w == null) {
            try {
                if (!connect(CONNECT_WAIT)) throw new SocketTimeoutException("the worker never connected");
            } catch (IOException e) {
                throw new UncheckedIOException("worker command channel: " + e.getMessage(), e);
            }
            w = out;
        }
        synchronized (this) {
            try {
                if (w == null) return;
                w.write(line);
                w.write('\n');
                w.flush();
            } catch (IOException ignored) {
                // The worker is gone; its output's end says so.
            }
        }
    }

    /** Close the channel: the worker reads end-of-input, as at {@code DONE}. */
    @Override
    public void closeInput() {
        close();
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        Socket s = socket;
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
            // Already closed: the worker is gone.
        }
    }
}
