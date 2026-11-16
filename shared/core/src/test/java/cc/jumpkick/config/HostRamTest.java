// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The host's RAM is read from meminfo, a smaller cgroup limit, else the JVM. */
class HostRamTest {

    @Test
    void meminfo_and_a_cgroup_limit_are_read(@TempDir Path tmp) throws Exception {
        Path meminfo =
                Files.writeString(tmp.resolve("meminfo"), "MemTotal:       32014848 kB\nMemFree:         1000 kB\n");
        assertThat(HostRam.meminfoTotal(meminfo)).isEqualTo(32014848L * 1024);
        assertThat(HostRam.meminfoTotal(tmp.resolve("absent"))).isEqualTo(-1);
        assertThat(HostRam.cgroupMax(Files.writeString(tmp.resolve("max"), "max\n")))
                .isEqualTo(-1);
        assertThat(HostRam.cgroupMax(Files.writeString(tmp.resolve("lim"), "8589934592\n")))
                .isEqualTo(8L << 30);
        assertThat(HostRam.totalBytes()).isPositive();
    }
}
