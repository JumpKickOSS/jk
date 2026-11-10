// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.ShortTempDirs;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An engine that stopped accepting makes a connect fail in bounded time, never hang. */
class EngineWireConnectTimeoutTest {

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkt-");

    @Test
    @Timeout(30)
    void a_listener_that_never_accepts_fails_the_connect_within_its_deadline() throws Exception {
        Path socket = tempDirs.create().resolve("gen1.sock");
        List<SocketChannel> queued = new ArrayList<>();
        try (ServerSocketChannel frozen = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            frozen.bind(UnixDomainSocketAddress.of(socket), 1); // and never accept
            assertThatThrownBy(() -> {
                        for (int i = 0; i < 64; i++) {
                            SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                            queued.add(ch);
                            EngineWire.connectWithin(ch, UnixDomainSocketAddress.of(socket), 300);
                        }
                    })
                    .as("once the queue is full a connect gives up rather than waiting")
                    .isInstanceOf(IOException.class);
            assertThat(queued).hasSizeLessThan(64);
        } finally {
            for (SocketChannel ch : queued) ch.close();
        }
    }
}
