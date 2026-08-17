// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfUpdateArtifactTest {

    @Test
    void prefers_xz_on_every_os() throws Exception {
        String sums = """
                aaa  jk-engine-0.12.0.jar
                bbb  jk-linux-x86_64.xz
                ccc  jk-windows-x86_64.xz
                ddd  jk-windows-x86_64.zip
                eee  jk-macos-aarch64.xz
                """;
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "linux", "x86_64"))
                .isEqualTo("jk-linux-x86_64.xz");
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "windows", "x86_64"))
                .isEqualTo("jk-windows-x86_64.xz");
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "macos", "aarch64"))
                .isEqualTo("jk-macos-aarch64.xz");
    }

    @Test
    void windows_falls_back_to_zip_when_sums_have_no_xz() throws Exception {
        String sums = "aaa  jk-engine-0.12.0.jar\nbbb  jk-windows-x86_64.zip\n";
        assertThat(SelfCommand.UpdateSub.pickClientArtifact(sums, "windows", "x86_64"))
                .isEqualTo("jk-windows-x86_64.zip");
    }

    @Test
    void ingest_client_reclaims_the_inflated_temp_binary(@TempDir Path tmp) throws Exception {
        // Cas.putFile copies (temp + atomic move) — without the delete, every successful
        // jk self update strands one native-binary-sized jk-self-*.bin in the system temp dir.
        Path client = Files.createTempFile(tmp, "jk-self-", ".bin");
        Files.write(client, new byte[] {1, 2, 3, 4});
        Cas cas = new Cas(tmp.resolve("cas"));
        String sha = SelfCommand.UpdateSub.ingestClient(cas, client);
        assertThat(cas.contains(sha)).isTrue();
        assertThat(client).doesNotExist();
    }

    @Test
    void unix_does_not_fall_back_to_zip() {
        String sums = "aaa  jk-linux-x86_64.zip\n";
        assertThatThrownBy(() -> SelfCommand.UpdateSub.pickClientArtifact(sums, "linux", "x86_64"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-linux-x86_64.xz");
    }
}
