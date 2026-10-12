// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A producer appends while the stream's writer thread drains. The writer reads the file with a
 * decoder that reports malformed input, so the bytes it sees must never end inside a multibyte
 * sequence a producer is still writing.
 */
class SpillFileTest {

    private static final int LINES = 400;

    @Test
    @Timeout(60)
    void a_drain_racing_appends_of_multibyte_lines_reads_every_line_whole(@TempDir Path dir) throws Exception {
        String pad = "é€𝄞".repeat(6_000);
        AtomicInteger appended = new AtomicInteger();
        try (SpillFile file = SpillFile.open(dir)) {
            Thread producer = Thread.ofPlatform().start(() -> {
                try {
                    for (int i = 0; i < LINES; i++) {
                        file.append(i + ":" + pad);
                        appended.incrementAndGet();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            for (int i = 0; i < LINES; i++) {
                while (appended.get() <= i) Thread.onSpinWait();
                String line = file.next();
                assertThat(line).isEqualTo(i + ":" + pad);
            }
            producer.join();
        }
    }
}
