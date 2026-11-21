// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostProcessorsTest {

    @Test
    void the_online_list_counts_ranges_and_single_ids() {
        assertThat(HostProcessors.parseOnline("0-23\n")).isEqualTo(24);
        assertThat(HostProcessors.parseOnline("0-3,8-11")).isEqualTo(8);
        assertThat(HostProcessors.parseOnline("0")).isEqualTo(1);
        assertThat(HostProcessors.parseOnline("0,2,4-5")).isEqualTo(4);
    }

    @Test
    void an_empty_or_malformed_list_is_refused_rather_than_read_as_zero() {
        assertThatThrownBy(() -> HostProcessors.parseOnline("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostProcessors.parseOnline("3-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostProcessors.parseOnline("a-b")).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void the_host_count_is_never_below_what_the_jvm_sees() {
        assertThat(HostProcessors.count())
                .isGreaterThanOrEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    void a_root_cpu_max_quota_narrows_the_online_count(@TempDir Path dir) throws Exception {
        Path online = Files.writeString(dir.resolve("online"), "0-19\n");
        Path cgroup = Files.createDirectories(dir.resolve("cgroup"));
        Files.writeString(cgroup.resolve("cpu.max"), "700000 100000\n");
        assertThat(HostProcessors.count(online, cgroup, 7)).isEqualTo(7);
        Files.writeString(cgroup.resolve("cpu.max"), "650000 100000\n");
        assertThat(HostProcessors.count(online, cgroup, 4))
                .as("a part processor rounds up")
                .isEqualTo(7);
    }

    @Test
    void an_unlimited_or_missing_root_quota_leaves_the_online_count(@TempDir Path dir) throws Exception {
        Path online = Files.writeString(dir.resolve("online"), "0-19\n");
        Path cgroup = Files.createDirectories(dir.resolve("cgroup"));
        assertThat(HostProcessors.count(online, cgroup, 7)).isEqualTo(20);
        Files.writeString(cgroup.resolve("cpu.max"), "max 100000\n");
        assertThat(HostProcessors.count(online, cgroup, 7)).isEqualTo(20);
        Files.writeString(cgroup.resolve("cpu.max"), "64000000 100000\n");
        assertThat(HostProcessors.count(online, cgroup, 7))
                .as("a quota above the host")
                .isEqualTo(20);
    }

    @Test
    void a_cgroup_v1_root_quota_narrows_the_online_count(@TempDir Path dir) throws Exception {
        Path online = Files.writeString(dir.resolve("online"), "0-23\n");
        Path cpu = Files.createDirectories(dir.resolve("cgroup").resolve("cpu,cpuacct"));
        Files.writeString(cpu.resolve("cpu.cfs_quota_us"), "400000\n");
        Files.writeString(cpu.resolve("cpu.cfs_period_us"), "100000\n");
        assertThat(HostProcessors.count(online, dir.resolve("cgroup"), 2)).isEqualTo(4);
        Files.writeString(cpu.resolve("cpu.cfs_quota_us"), "-1\n");
        assertThat(HostProcessors.count(online, dir.resolve("cgroup"), 2)).isEqualTo(24);
    }

    @Test
    void the_jvm_count_stands_above_a_root_quota_and_without_an_online_list(@TempDir Path dir) throws Exception {
        Path online = Files.writeString(dir.resolve("online"), "0-19\n");
        Path cgroup = Files.createDirectories(dir.resolve("cgroup"));
        Files.writeString(cgroup.resolve("cpu.max"), "200000 100000\n");
        assertThat(HostProcessors.count(online, cgroup, 8)).isEqualTo(8);
        assertThat(HostProcessors.count(dir.resolve("missing"), cgroup, 8)).isEqualTo(8);
    }
}
