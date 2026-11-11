// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolWriterTest {

    /** Each write the stream under the buffer receives, as the descriptor would see it. */
    private static final class Writes extends OutputStream {
        final List<String> calls = new ArrayList<>();

        @Override
        public void write(int b) {
            calls.add(String.valueOf((char) b));
        }

        @Override
        public void write(byte[] b, int off, int len) {
            calls.add(new String(b, off, len, StandardCharsets.UTF_8));
        }
    }

    /**
     * A line longer than the buffer still reaches the descriptor in one write: cut at the buffer's
     * edge, anything else the worker prints there could land in the middle of the JSON.
     */
    @Test
    void aLineLongerThanTheBufferIsOneWrite() {
        Writes fd = new Writes();
        PrintStream out = new PrintStream(new BufferedOutputStream(fd, 8192), false, StandardCharsets.UTF_8);
        ProtocolWriter writer = new ProtocolWriter(out, "##JKTEST:");
        String json = "{\"stack\":\"" + "é".repeat(12_000) + "\"}";

        writer.emit("{}");
        writer.emit(json);

        assertThat(fd.calls)
                .containsExactly("##JKTEST:{}" + System.lineSeparator(), "##JKTEST:" + json + System.lineSeparator());
    }
}
