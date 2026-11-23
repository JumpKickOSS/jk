// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.contract;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * An engine stand-in on a Unix-domain socket that answers each request line with the replies of
 * the first of its cases that {@linkplain WireCase#answers answers} it, on as many connections as
 * the client opens. It records every case it served and every way a request failed to conform; a
 * request no case answers is recorded and its connection closed.
 */
public final class WireContractStub implements AutoCloseable {

    private final Path socket;
    private final List<WireCase> cases;
    private final ServerSocketChannel listener;
    private final List<String> served = new CopyOnWriteArrayList<>();
    private final List<String> mismatches = new CopyOnWriteArrayList<>();

    /** Listen on {@code socket} and serve {@code cases}, the first that answers a request winning. */
    public WireContractStub(Path socket, List<WireCase> cases) throws IOException {
        this.socket = socket;
        this.cases = List.copyOf(cases);
        Files.createDirectories(socket.toAbsolutePath().getParent());
        listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        listener.bind(UnixDomainSocketAddress.of(socket));
        Thread.ofPlatform().daemon().name("wire-contract-stub").start(this::acceptLoop);
    }

    /** The names of the cases served so far, one entry per request answered, in order. */
    public List<String> served() {
        return List.copyOf(served);
    }

    /** Every way a request the client wrote failed to conform, and every request no case answered. */
    public List<String> mismatches() {
        return List.copyOf(mismatches);
    }

    private void acceptLoop() {
        while (listener.isOpen()) {
            try {
                SocketChannel ch = listener.accept();
                Thread.ofPlatform().daemon().name("wire-contract-conn").start(() -> serve(ch));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(SocketChannel ch) {
        try (ch;
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                WireCase answering = answering(line);
                if (answering == null) {
                    mismatches.add("no case answers the request " + line);
                    return;
                }
                served.add(answering.name());
                mismatches.addAll(answering.requestMismatches(line));
                for (String reply : answering.replies()) {
                    out.write(reply);
                    out.write('\n');
                }
                out.flush();
            }
        } catch (IOException gone) {
            // the client closed its end
        }
    }

    private @Nullable WireCase answering(String line) {
        for (WireCase c : cases) if (c.answers(line)) return c;
        return null;
    }

    @Override
    public void close() throws IOException {
        listener.close();
        Files.deleteIfExists(socket);
    }
}
