// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;

class DeterministicJarTest {

    private static byte[] twoEntryJar(long epoch) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(out)) {
            DeterministicJar.writeEntry(jos, "b.txt", "beta".getBytes(), epoch);
            DeterministicJar.writeEntryStreaming(jos, "a.txt", new ByteArrayInputStream("alpha".getBytes()), epoch);
        }
        return out.toByteArray();
    }

    @Test
    void stamps_every_entry_with_the_fixed_epoch() throws IOException {
        long epoch = 1_000_000_000L; // 2001-09-09T01:46:40Z
        LocalDateTime expected = LocalDateTime.ofEpochSecond(epoch, 0, ZoneOffset.UTC);
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(twoEntryJar(epoch)))) {
            JarEntry e;
            int seen = 0;
            while ((e = in.getNextJarEntry()) != null) {
                assertThat(e.getTimeLocal()).isEqualTo(expected);
                seen++;
            }
            assertThat(seen).isEqualTo(2);
        }
    }

    @Test
    void identical_inputs_produce_byte_identical_jars() throws IOException {
        assertThat(twoEntryJar(0L)).isEqualTo(twoEntryJar(0L));
    }
}
