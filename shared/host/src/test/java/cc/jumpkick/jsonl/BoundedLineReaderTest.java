// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The idle bound: one env definition for both ends, switchable between reads, and visible after it fires. */
class BoundedLineReaderTest {

    @Test
    void stream_idle_reads_millis_then_minutes_then_the_default() {
        assertThat(BoundedLineReader.streamIdleMillis(k -> null)).isEqualTo(BoundedLineReader.DEFAULT_STREAM_IDLE_MS);
        assertThat(BoundedLineReader.streamIdleMillis(Map.of("JK_STREAM_IDLE_MS", "1500")::get))
                .isEqualTo(1500);
        assertThat(BoundedLineReader.streamIdleMillis(Map.of("JK_STREAM_IDLE_MINUTES", "2")::get))
                .isEqualTo(120_000);
        assertThat(BoundedLineReader.streamIdleMillis(
                        Map.of("JK_STREAM_IDLE_MS", "0", "JK_STREAM_IDLE_MINUTES", "5")::get))
                .as("milliseconds win, and 0 means off")
                .isZero();
        assertThat(BoundedLineReader.streamIdleMillis(Map.of("JK_STREAM_IDLE_MS", "soon")::get))
                .isEqualTo(BoundedLineReader.DEFAULT_STREAM_IDLE_MS);
    }

    @Test
    void a_stalled_read_closes_the_peer_and_reports_the_timeout() throws IOException {
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel peer = SocketChannel.open(listener.getLocalAddress());
                    SocketChannel accepted = listener.accept()) {
                BoundedLineReader reader = new BoundedLineReader(
                        new InputStreamReader(Channels.newInputStream(accepted), StandardCharsets.UTF_8), accepted, 0);
                peer.write(ByteBuffer.wrap("hello\n".getBytes(StandardCharsets.UTF_8)));
                assertThat(reader.readLine()).as("no bound: a normal line").isEqualTo("hello");
                assertThat(reader.timedOut()).isFalse();

                reader.idleTimeout(100);
                assertThatThrownBy(reader::readLine)
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("no protocol traffic");
                assertThat(reader.timedOut()).isTrue();
                assertThat(accepted.isOpen()).as("the timer closed the peer").isFalse();
            }
        }
    }
}
