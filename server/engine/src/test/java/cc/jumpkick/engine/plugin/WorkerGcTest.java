// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The GC log's largest before-occupancy, not region counts. */
class WorkerGcTest {

    @Test
    void the_largest_before_occupancy_wins_and_region_counts_do_not() {
        String log = """
                [0.145s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 14M->2M(256M) 3.456ms
                [0.200s][info][gc,heap] GC(0) Eden regions: 24->0(24)
                [1.002s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 128M->40M(256M) 8.1ms
                [0.032s][info][gc] GC(2) Garbage Collection (Warmup) 16M(2%)->8M(1%) 1.2ms
                [2.000s][info][gc] GC(3) Pause Young (Allocation Failure) 512K->128K(256M) 1.0ms
                """;
        assertThat(WorkerGc.peak(log)).isEqualTo(128L << 20);
    }

    @Test
    void gigabyte_sizes_and_an_empty_log_are_read() {
        assertThat(WorkerGc.peak("[gc] GC(0) Pause Full (System.gc()) 1.5G->800M(2G) 20ms"))
                .isEqualTo((long) (1.5 * (1L << 30)));
        assertThat(WorkerGc.peak("")).isZero();
        assertThat(WorkerGc.peak((String) null)).isZero();
    }

    @Test
    void vm_hwm_is_kibibytes_from_the_status_line() {
        assertThat(WorkerGc.vmHwmBytes("Name:\tjava\nVmHWM:\t  204800 kB\nVmRSS:\t  1024 kB\n"))
                .isEqualTo(204800L * 1024L);
        assertThat(WorkerGc.vmHwmBytes("")).isZero();
        assertThat(WorkerGc.vmHwmBytes(null)).isZero();
    }
}
