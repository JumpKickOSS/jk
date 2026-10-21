// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.HostPressure.Level;
import org.junit.jupiter.api.Test;

class HostPressureTest {

    @Test
    void a_pressure_stall_file_reads_as_a_level() {
        assertThat(HostPressure.psiLevel("""
                some avg10=0.00 avg60=0.00 avg300=0.00 total=0
                full avg10=0.00 avg60=0.00 avg300=0.00 total=0
                """)).isEqualTo(Level.NORMAL);
        assertThat(HostPressure.psiLevel("""
                some avg10=12.50 avg60=3.00 avg300=1.00 total=99
                full avg10=2.00 avg60=0.00 avg300=0.00 total=9
                """)).isEqualTo(Level.WARN);
        assertThat(HostPressure.psiLevel("""
                some avg10=40.00 avg60=3.00 avg300=1.00 total=99
                full avg10=15.00 avg60=0.00 avg300=0.00 total=9
                """)).isEqualTo(Level.CRITICAL);
        assertThat(HostPressure.psiLevel("garbage")).isEqualTo(Level.NORMAL);
    }

    @Test
    void the_macos_kernel_levels_map_to_levels() {
        assertThat(HostPressure.macLevel(1)).isEqualTo(Level.NORMAL);
        assertThat(HostPressure.macLevel(2)).isEqualTo(Level.WARN);
        assertThat(HostPressure.macLevel(4)).isEqualTo(Level.CRITICAL);
        assertThat(HostPressure.macLevel(0)).isEqualTo(Level.NORMAL);
    }

    @Test
    void this_host_has_a_level() {
        assertThat(HostPressure.level()).isNotNull();
    }
}
