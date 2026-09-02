// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvValuesTest {

    @Test
    void isCi_reads_the_one_truth_set_defaulting_off() {
        assertThat(EnvValues.isCi(Map.of("CI", "true")::get)).isTrue();
        assertThat(EnvValues.isCi(Map.of("CI", "1")::get)).isTrue();
        assertThat(EnvValues.isCi(Map.of("CI", "yes")::get)).isTrue();
        assertThat(EnvValues.isCi(Map.of("CI", "false")::get))
                .as("CI=false is not CI — the presence tests this replaced said otherwise")
                .isFalse();
        assertThat(EnvValues.isCi(Map.of("CI", "0")::get)).isFalse();
        assertThat(EnvValues.isCi(name -> null)).isFalse();
    }
}
