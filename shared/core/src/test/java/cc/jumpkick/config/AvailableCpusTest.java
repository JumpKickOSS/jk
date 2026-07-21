// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AvailableCpusTest {

    @AfterEach
    void clearProp() {
        System.clearProperty(AvailableCpus.PROP);
    }

    @Test
    void coresFromQuota_ceil() {
        assertThat(AvailableCpus.coresFromQuota(100_000, 100_000)).isEqualTo(1);
        assertThat(AvailableCpus.coresFromQuota(200_000, 100_000)).isEqualTo(2);
        assertThat(AvailableCpus.coresFromQuota(150_000, 100_000)).isEqualTo(2); // ceil
        assertThat(AvailableCpus.coresFromQuota(-1, 100_000)).isEqualTo(-1);
        assertThat(AvailableCpus.coresFromQuota(100_000, 0)).isEqualTo(-1);
    }

    @Test
    void readCpuMax_max_is_unlimited(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("cpu.max");
        Files.writeString(f, "max 100000\n");
        assertThat(AvailableCpus.readCpuMax(f)).isEqualTo(-1);
    }

    @Test
    void readCpuMax_quota(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("cpu.max");
        Files.writeString(f, "400000 100000\n");
        assertThat(AvailableCpus.readCpuMax(f)).isEqualTo(4);
    }

    @Test
    void v2_root_cpu_max(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("cpu.max"), "200000 100000\n");
        assertThat(AvailableCpus.count(root, root.resolve("missing-proc"), () -> 64)).isEqualTo(2);
    }

    @Test
    void v2_nested_via_proc_self_cgroup(@TempDir Path root) throws Exception {
        Path slice = root.resolve("system.slice/docker-abc.scope");
        Files.createDirectories(slice);
        Files.writeString(slice.resolve("cpu.max"), "300000 100000\n");
        Path proc = root.resolve("proc-self-cgroup");
        Files.writeString(proc, "0::/system.slice/docker-abc.scope\n");
        // no root cpu.max → nested only
        assertThat(AvailableCpus.count(root, proc, () -> 64)).isEqualTo(3);
    }

    @Test
    void v1_cfs_quota(@TempDir Path root) throws Exception {
        Path cpu = root.resolve("cpu");
        Files.createDirectories(cpu);
        Files.writeString(cpu.resolve("cpu.cfs_quota_us"), "200000\n");
        Files.writeString(cpu.resolve("cpu.cfs_period_us"), "100000\n");
        assertThat(AvailableCpus.count(root, root.resolve("nope"), () -> 16)).isEqualTo(2);
    }

    @Test
    void v1_unlimited_falls_back_to_jvm(@TempDir Path root) throws Exception {
        Path cpu = root.resolve("cpu");
        Files.createDirectories(cpu);
        Files.writeString(cpu.resolve("cpu.cfs_quota_us"), "-1\n");
        Files.writeString(cpu.resolve("cpu.cfs_period_us"), "100000\n");
        assertThat(AvailableCpus.count(root, root.resolve("nope"), () -> 12)).isEqualTo(12);
    }

    @Test
    void missing_cgroup_falls_back_to_jvm(@TempDir Path root) {
        assertThat(AvailableCpus.count(root.resolve("absent"), root.resolve("nope"), () -> 7)).isEqualTo(7);
    }

    @Test
    void system_property_wins(@TempDir Path root) {
        System.setProperty(AvailableCpus.PROP, "5");
        assertThat(AvailableCpus.count(root, root.resolve("nope"), () -> 64)).isEqualTo(5);
    }

    @Test
    void jobs_default_uses_available_cpus_probe() {
        System.setProperty(AvailableCpus.PROP, "9");
        assertThat(Jobs.effective(null)).isEqualTo(9);
        assertThat(Jobs.effective(0)).isEqualTo(9);
        assertThat(Jobs.effective(2)).isEqualTo(2);
    }
}
