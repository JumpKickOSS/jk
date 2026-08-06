// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestEffortTest {

    @Test
    void class_walls_skip_method_product() {
        Map<String, Long> walls = Map.of("com.ex.A", 1000L, "com.ex.B", 2000L);
        long ms = TestEffort.wallMillis(
                "/m", walls, List.of("com.ex.A", "com.ex.B"), /*methodCount*/ 9999, null, List.of(), null, 1);
        // startup + 3000; method count must not be used
        assertThat(ms).isGreaterThanOrEqualTo(3000);
        assertThat(ms).isLessThan(3000 + 9999 * 40L); // would be huge if methods used
    }

    @Test
    void incomplete_class_walls_uses_method_count() {
        Map<String, Long> walls = Map.of("com.ex.A", 1000L); // B missing
        long withMethods = TestEffort.wallMillis(
                "/m", walls, List.of("com.ex.A", "com.ex.B"), 10, null, List.of(), null, 1);
        long noMethods = TestEffort.wallMillis(
                "/m", walls, List.of("com.ex.A", "com.ex.B"), 0, null, List.of(), null, 1);
        assertThat(withMethods).isGreaterThan(noMethods);
    }

    @Test
    void empty_selection_does_not_require_method_count() {
        long ms = TestEffort.wallMillis("/m", Map.of(), List.of(), 0, null, List.of(), null, 1);
        assertThat(ms).isPositive();
    }
}
