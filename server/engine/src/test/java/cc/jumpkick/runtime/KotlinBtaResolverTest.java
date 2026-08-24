// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Kotlin-specific half of tool resolution. The closure cache the two resolvers share is
 * {@link ToolClosureTest}; this pins the floor that is Kotlin's alone, so a Kotlin-only regression
 * cannot pass behind a Groovy green.
 */
class KotlinBtaResolverTest {

    @Test
    void enforces_the_2_4_0_floor() {
        // Below the floor: the worker's KotlinToolchains entry point doesn't exist.
        for (String tooOld : List.of("2.3.21", "2.0.0", "1.9.24", "2.3.99")) {
            assertThatThrownBy(() -> KotlinBtaResolver.requireSupportedVersion(tooOld))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2.4.0");
        }
        // At or above the floor: accepted (incl. pre-release and future majors).
        for (String ok : List.of("2.4.0", "2.4.20", "2.5.0", "2.4.0-RC2", "3.0.0")) {
            assertThatCode(() -> KotlinBtaResolver.requireSupportedVersion(ok)).doesNotThrowAnyException();
        }
    }
}
