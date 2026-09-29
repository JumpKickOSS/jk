// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A test that provokes an {@link OutOfMemoryError} and catches it, as buffer-overflow tests in
 * libraries do. It passes; a test JVM that exited on the first such error would fail it. The array
 * is over the VM's size limit, so no heap is allocated.
 */
class CaughtOutOfMemoryFixture {

    @Test
    void an_oversized_array_is_refused_and_the_test_carries_on() {
        OutOfMemoryError caught = null;
        try {
            long[] huge = new long[Integer.MAX_VALUE];
            assertThat(huge).isEmpty();
        } catch (OutOfMemoryError e) {
            caught = e;
        }
        assertThat(caught).isNotNull();
    }
}
