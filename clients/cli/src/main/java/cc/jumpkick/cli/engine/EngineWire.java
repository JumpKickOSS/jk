// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-shot JSONL I/O against a live engine socket. Long-lived plan streams use
 * {@link #protocolReader} on their own connection.
 */
public final class EngineWire {

    /** Per-read/connect socket timeout — a live engine replies in well under this. */
    static final int SOCKET_TIMEOUT_MILLIS = 2_000;

    private EngineWire() {}

    /**
     * The client-side protocol reader: line-capped, and idle-timed so a dead engine surfaces as
     * an error instead of a forever-blocked {@code readLine}. Default 60 minutes between
     * events. Tune with {@code JK_STREAM_IDLE_MS} (milliseconds, preferred) or {@code
     * JK_STREAM_IDLE_MINUTES} (0 disables).
     */
    static BufferedReader protocolReader(SocketChannel ch) {
        long idleMs = 60L * 60_000L;
        String envMs = System.getenv("JK_STREAM_IDLE_MS");
        if (envMs != null && !envMs.isBlank()) {
            try {
                idleMs = Long.parseLong(envMs.trim());
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        } else {
            String env = System.getenv("JK_STREAM_IDLE_MINUTES");
            if (env != null && !env.isBlank()) {
                try {
                    idleMs = Long.parseLong(env.trim()) * 60_000L;
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
            }
        }
        return new cc.jumpkick.jsonl.BoundedLineReader(
                new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8), ch, idleMs);
    }

    /**
     * Package-visible: {@link EngineBuildListenerAdapter} opens its own long-lived connection. On
     * the loopback-TCP transport (Windows — see {@link cc.jumpkick.engine.EngineTransport}), {@code
     * socket} holds the port number (not a real socket path) and this also sends the required
     * {@link EngineProtocol#AUTH} line before returning, so every caller authenticates transparently
     * without needing its own knowledge of the transport.
     */
    static SocketChannel connect(Path socket) throws IOException {
        if (cc.jumpkick.engine.EngineTransport.useLoopbackTcp()) {
            int port = Integer.parseInt(Files.readString(socket).trim());
            String token = Files.readString(cc.jumpkick.engine.EnginePaths.tokenFor(socket))
                    .trim();
            SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            BufferedWriter authWriter =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            authWriter.write(ProtoLifecycle.auth(token));
            authWriter.write('\n');
            authWriter.flush();
            return ch;
        }
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(socket));
        return ch;
    }

    /**
     * Send one line, read one reply line, over an already-connected channel. {@link SocketChannel}
     * has no built-in read timeout, so a watchdog thread closes the channel if the engine doesn't
     * reply in time.
     */
    static String exchange(SocketChannel ch, String line) throws IOException {
        BufferedWriter writer =
                new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
        writer.write(line);
        writer.write('\n');
        writer.flush();
        BufferedReader reader = protocolReader(ch);
        Thread watchdog = new Thread(
                () -> {
                    try {
                        Thread.sleep(SOCKET_TIMEOUT_MILLIS);
                        ch.close();
                    } catch (InterruptedException ignored) {
                        // exchange finished in time — nothing to do
                    } catch (IOException ignored) {
                        // already closing
                    }
                },
                "jk-engine-client-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            String reply = reader.readLine();
            if (reply == null) throw new IOException("engine closed the connection without replying");
            return reply;
        } catch (AsynchronousCloseException e) {
            throw new IOException("engine did not reply within " + SOCKET_TIMEOUT_MILLIS + "ms", e);
        } finally {
            watchdog.interrupt();
        }
    }
}
