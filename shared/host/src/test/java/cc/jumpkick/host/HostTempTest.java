// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/** jk's own processes keep temporary files on disk: /var/tmp on Linux, where /tmp is often RAM. */
class HostTempTest {

    @Test
    void linux_with_a_writable_var_tmp_moves_to_it() {
        assertThat(HostTemp.override(true, p -> true, p -> true)).isEqualTo("/var/tmp");
    }

    @Test
    void other_systems_keep_the_jvm_default() {
        assertThat(HostTemp.override(false, p -> true, p -> true)).isNull();
    }

    @Test
    void a_missing_or_read_only_var_tmp_keeps_the_jvm_default() {
        assertThat(HostTemp.override(true, p -> false, p -> true)).isNull();
        assertThat(HostTemp.override(true, p -> true, p -> false)).isNull();
    }

    @Test
    void a_tmpdir_the_caller_names_is_theirs() {
        assertThat(HostTemp.named(List.of("-Xmx1g", "-Djava.io.tmpdir=/scratch")))
                .isTrue();
        assertThat(HostTemp.named(List.of("-J-Djava.io.tmpdir=/scratch"))).isTrue();
        assertThat(HostTemp.named(List.of("-Xmx1g", "-Djava.io.tmpdirx"))).isFalse();
    }

    @Test
    void the_flag_names_the_directory_the_process_uses() {
        String flag = HostTemp.jvmFlag();
        if (flag == null) {
            assertThat(HostTemp.override(Os.isLinux(), Files::isDirectory, Files::isWritable))
                    .isNull();
        } else {
            assertThat(flag).isEqualTo("-Djava.io.tmpdir=" + HostTemp.dir());
        }
    }
}
