// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.kotlin.KotlinResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Kotlin-specific half of tool resolution. The closure cache the two resolvers share is
 * {@link ToolClosureTest}; this pins the floor that is Kotlin's alone, so a Kotlin-only regression
 * cannot pass behind a Groovy green.
 */
class KotlinBtaResolverTest {

    @Test
    void enforces_the_2_4_10_floor() {
        // Below the floor: either the worker's KotlinToolchains entry point doesn't exist, or the
        // release is 2.4.0, whose K2 frontend cannot compile a script using @file:Import.
        for (String tooOld : List.of("2.3.21", "2.0.0", "1.9.24", "2.3.99", "2.4.0", "2.4.9")) {
            assertThatThrownBy(() -> KotlinBtaResolver.requireSupportedVersion(tooOld))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(KotlinResolver.FLOOR_VERSION);
        }
        // At or above the floor: accepted (incl. later patches, minors and future majors).
        for (String ok : List.of("2.4.10", "2.4.20", "2.5.0", "3.0.0")) {
            assertThatCode(() -> KotlinBtaResolver.requireSupportedVersion(ok)).doesNotThrowAnyException();
        }
    }

    /**
     * A pre-release of the floor sorts below it. {@code 2.4.0-RC2} used to be accepted by a
     * major/minor-only guard, and it carries exactly the @file:Import bug the floor exists to
     * exclude — so the patch-level comparison has to reject it.
     */
    @Test
    void rejects_a_pre_release_of_the_broken_floor() {
        assertThatThrownBy(() -> KotlinBtaResolver.requireSupportedVersion("2.4.0-RC2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(KotlinResolver.FLOOR_VERSION);
    }
}
