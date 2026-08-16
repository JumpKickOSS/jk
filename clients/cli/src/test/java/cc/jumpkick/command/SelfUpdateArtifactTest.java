// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

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
    void unix_does_not_fall_back_to_zip() {
        String sums = "aaa  jk-linux-x86_64.zip\n";
        assertThatThrownBy(() -> SelfCommand.UpdateSub.pickClientArtifact(sums, "linux", "x86_64"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("jk-linux-x86_64.xz");
    }
}
