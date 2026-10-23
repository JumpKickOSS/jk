// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A green marker is one whose failure count reads as zero; one that will not read is not green. */
class TestStampGreenTest {

    private static ActionCache.ActionRecord record(Map<String, String> outputs) {
        return new ActionCache.ActionRecord("run-tests", "k", Map.of(), outputs, Set.of());
    }

    @Test
    void a_torn_failure_count_is_not_green() {
        assertThat(TestStamp.green(record(Map.of(TestStamp.FAILED, "1x")))).isFalse();
        assertThat(TestStamp.green(record(Map.of(TestStamp.FAILED, "0")))).isTrue();
        assertThat(TestStamp.green(record(Map.of(TestStamp.FAILED, "2")))).isFalse();
    }
}
