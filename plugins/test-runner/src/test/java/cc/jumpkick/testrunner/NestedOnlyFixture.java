// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** A class whose only test lives in a {@code @Nested} class; see {@code TestClassShapeTest}. */
class NestedOnlyFixture {

    @Nested
    class Inner {
        @Test
        void inner() {}
    }
}
