// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandChannelTest {

    @Test
    void the_worker_sends_its_token_first_and_then_reads_the_commands() throws Exception {
        try (ServerSocket engine = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            String address = engine.getInetAddress().getHostAddress() + ":" + engine.getLocalPort();
            List<String> args = List.of("--serve", CommandChannel.COMMANDS + address, CommandChannel.TOKEN + "abc123");
            try (BufferedReader commands = CommandChannel.connect(args);
                    Socket worker = engine.accept()) {
                BufferedReader hello =
                        new BufferedReader(new InputStreamReader(worker.getInputStream(), StandardCharsets.US_ASCII));
                assertThat(hello.readLine()).isEqualTo("abc123");
                Writer out = new OutputStreamWriter(worker.getOutputStream(), StandardCharsets.UTF_8);
                out.write("RUN /tmp/spec\nDONE\n");
                out.flush();
                worker.shutdownOutput();
                assertThat(commands.readLine()).isEqualTo("RUN /tmp/spec");
                assertThat(commands.readLine()).isEqualTo("DONE");
                assertThat(commands.readLine())
                        .as("a closed channel is end-of-input")
                        .isNull();
            }
        }
    }

    @Test
    void a_worker_with_no_channel_named_is_refused() {
        assertThatThrownBy(() -> CommandChannel.connect(List.of("--serve")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CommandChannel.COMMANDS);
    }
}
