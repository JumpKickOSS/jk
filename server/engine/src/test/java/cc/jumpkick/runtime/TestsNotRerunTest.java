// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestsNotRerunTest {

    @Test
    void the_count_reads_back_from_the_message_it_wrote() {
        assertThat(TestsNotRerun.message(1)).startsWith("1 test class passed earlier");
        assertThat(TestsNotRerun.count(TestsNotRerun.message(1))).isEqualTo(1);
        assertThat(TestsNotRerun.count(TestsNotRerun.message(1234))).isEqualTo(1234);
        assertThat(TestsNotRerun.count("no number here")).isZero();
        assertThat(TestsNotRerun.count("")).isZero();
    }
}
