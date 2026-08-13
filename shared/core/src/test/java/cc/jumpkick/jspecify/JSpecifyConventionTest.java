// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jspecify;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code org.jspecify} is on the test compile classpath (java-conventions
 * {@code testCompileOnly}). {@code @NullMarked} is CLASS-retention — this file compiling is the
 * assertion; do not look the annotation up at runtime.
 */
@NullMarked
class JSpecifyConventionTest {

    @Test
    void compiles_against_jspecify() {
        assertThat(2).isEqualTo(2);
    }
}
