// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.wire.EngineHandshake;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dialling a test engine the way a real client does.
 *
 * <p>One place, because there were three, and each of them opened a Unix socket unconditionally.
 * The engine picks its transport from {@link EngineTransport}: a Unix socket where that works, and
 * on Windows a loopback TCP listener whose port sits in the "socket" file with a token beside it.
 * A test client that only speaks the first tests nothing on the second — it just gets connection
 * refused, which is what the whole engine tier did on Windows.
 */
final class EngineSockets {

    private EngineSockets() {}

    /** Connect to the engine at {@code socket}, running the handshake when the transport wants one. */
    static SocketChannel connect(Path socket) throws IOException {
        if (!EngineTransport.useLoopbackTcp()) {
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(socket));
            return ch;
        }
        int port = port(socket);
        String token = Files.readString(EnginePaths.tokenFor(socket)).trim();
        SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        try {
            EngineHandshake.client(ch, token, EngineHandshake.ACK_TIMEOUT_MILLIS);
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        return ch;
    }

    /**
     * The port a live engine wrote, as an {@link IOException} when there isn't one. A killed engine
     * can leave the file empty or half-written, and "no port here" means the same thing as nothing
     * listening — callers already handle that, where an escaping {@code NumberFormatException}
     * would take the whole probe down.
     */
    private static int port(Path socket) throws IOException {
        String raw = Files.readString(socket).trim();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException notAPort) {
            throw new IOException("no engine port in " + socket + " (stale or half-written)", notAPort);
        }
    }
}
