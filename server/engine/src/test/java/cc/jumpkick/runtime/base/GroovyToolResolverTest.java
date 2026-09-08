// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Groovy-specific half of tool resolution. The closure cache the two resolvers share is
 * {@link ToolClosureTest}; this pins the floor that is Groovy's alone, so a Groovy-only regression
 * cannot pass behind a Kotlin green.
 */
class GroovyToolResolverTest {

    @Test
    void enforces_the_5_0_floor() {
        // Below the floor: the worker drives Groovy 5 compiler APIs.
        for (String tooOld : List.of("4.0.28", "3.0.25", "2.5.23")) {
            assertThatThrownBy(() -> GroovyToolResolver.requireSupportedVersion(tooOld))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("5.0");
        }
        // At or above the floor: accepted (incl. pre-release and future majors).
        for (String ok : List.of("5.0.0", "5.0.4", "5.1.0", "5.0.0-alpha-1", "6.0.0")) {
            assertThatCode(() -> GroovyToolResolver.requireSupportedVersion(ok)).doesNotThrowAnyException();
        }
    }
}
