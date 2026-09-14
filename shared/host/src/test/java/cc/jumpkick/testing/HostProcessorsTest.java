// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

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
}
