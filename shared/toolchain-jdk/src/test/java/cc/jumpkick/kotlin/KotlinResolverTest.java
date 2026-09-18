// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The floor that is Kotlin's alone: a version below it is not a refusal but the floor itself, the
 * compiler jk drives for it, with one sentence saying so.
 */
class KotlinResolverTest {

    @Test
    void a_version_below_the_2_4_10_floor_is_raised_to_it() {
        // Below the floor: either the worker's KotlinToolchains entry point does not exist, or the
        // release is 2.4.0, whose K2 frontend cannot compile a script using @file:Import.
        for (String tooOld : List.of("2.3.21", "2.2.21", "2.0.0", "1.9.24", "2.3.99", "2.4.0", "2.4.9")) {
            assertThat(KotlinResolver.belowFloor(tooOld)).as(tooOld).isTrue();
            assertThat(KotlinResolver.floored(tooOld)).as(tooOld).isEqualTo(KotlinResolver.FLOOR_VERSION);
        }
        // At or above the floor: the declared version, later patches, minors and majors included.
        for (String ok : List.of("2.4.10", "2.4.20", "2.5.0", "3.0.0")) {
            assertThat(KotlinResolver.belowFloor(ok)).as(ok).isFalse();
            assertThat(KotlinResolver.floored(ok)).as(ok).isEqualTo(ok);
        }
    }

    /**
     * A pre-release of the floor sorts below it. {@code 2.4.0-RC2} carries the {@code @file:Import}
     * bug the floor exists to exclude, so it compiles with the floor too.
     */
    @Test
    void a_pre_release_of_the_broken_floor_is_below_it() {
        assertThat(KotlinResolver.belowFloor("2.4.0-RC2")).isTrue();
        assertThat(KotlinResolver.floored("2.4.0-RC2")).isEqualTo(KotlinResolver.FLOOR_VERSION);
    }

    @Test
    void the_floor_note_names_the_declared_version_the_floor_and_the_key_to_write() {
        assertThat(KotlinResolver.floorNote("2.2.21"))
                .startsWith("kotlin 2.2.21 is below jk's floor 2.4.10")
                .contains("compiles with 2.4.10")
                .contains("kotlin = \"2.4.10\"");
    }
}
