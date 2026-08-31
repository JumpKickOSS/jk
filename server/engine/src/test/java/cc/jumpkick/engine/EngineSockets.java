// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
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

    /** Connect to the engine at {@code socket}, sending the auth envelope when the transport wants one. */
    static SocketChannel connect(Path socket) throws IOException {
        if (!EngineTransport.useLoopbackTcp()) {
            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            ch.connect(UnixDomainSocketAddress.of(socket));
            return ch;
        }
        int port = port(socket);
        String token = Files.readString(EnginePaths.tokenFor(socket)).trim();
        SocketChannel ch = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        // The auth envelope is the first line on the wire; the engine refuses everything else
        // until it has seen a matching token.
        BufferedWriter auth =
                new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
        auth.write(ProtoLifecycle.auth(token));
        auth.write('\n');
        auth.flush();
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
