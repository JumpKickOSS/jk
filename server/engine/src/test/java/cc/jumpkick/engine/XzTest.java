// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

class XzTest {

    @TempDir
    Path tmp;

    @Test
    void inflate_round_trips_bytes() throws Exception {
        byte[] raw = "jk-client-binary\n".repeat(200).getBytes(StandardCharsets.UTF_8);
        Path xz = tmp.resolve("jk.xz");
        Path out = tmp.resolve("jk");
        try (var dest = new XZOutputStream(Files.newOutputStream(xz), new LZMA2Options())) {
            dest.write(raw);
        }
        Xz.inflate(xz, out);
        assertThat(out).hasBinaryContent(raw);
    }

    @Test
    void inflate_rejects_non_xz() throws Exception {
        Path junk = tmp.resolve("not.xz");
        Files.writeString(junk, "not xz");
        assertThatThrownBy(() -> Xz.inflate(junk, tmp.resolve("out"))).isInstanceOf(IOException.class);
    }

    @Test
    void main_flag_writes_the_inflated_file() throws Exception {
        byte[] raw = "native-jk".getBytes(StandardCharsets.UTF_8);
        Path xz = tmp.resolve("in.xz");
        Path out = tmp.resolve("out.bin");
        try (var dest = new XZOutputStream(Files.newOutputStream(xz), new LZMA2Options())) {
            dest.write(raw);
        }
        assertThat(EngineMain.runInflateXz(new String[] {"--inflate-xz", xz.toString(), out.toString()}))
                .isZero();
        assertThat(out).hasBinaryContent(raw);
    }

    @Test
    void main_flag_requires_two_paths() {
        // 64 is Exit.USAGE, spelled as the literal a shell would see; moved this off 2,
        // which now means only "bad config".
        assertThat(EngineMain.runInflateXz(new String[] {"--inflate-xz"})).isEqualTo(64);
    }
}
