// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The floor that is Groovy's alone: a version below it is not a refusal but the bundled default,
 * the compiler jk drives for it, with one sentence saying so.
 */
class GroovyResolverTest {

    @Test
    void a_version_below_the_5_0_floor_compiles_with_the_default() {
        // Below the floor: the groovy-compiler worker drives the Groovy 5 compiler APIs.
        for (String tooOld : List.of("4.0.28", "3.0.25", "2.5.23", "5.0.0-alpha-1")) {
            assertThat(GroovyResolver.belowFloor(tooOld)).as(tooOld).isTrue();
            assertThat(GroovyResolver.floored(tooOld)).as(tooOld).isEqualTo(GroovyResolver.DEFAULT_VERSION);
        }
        // At or above the floor: the declared version, later patches, minors and majors included.
        for (String ok : List.of("5.0.0", "5.0.4", "5.1.0", "6.0.0")) {
            assertThat(GroovyResolver.belowFloor(ok)).as(ok).isFalse();
            assertThat(GroovyResolver.floored(ok)).as(ok).isEqualTo(ok);
        }
    }

    @Test
    void the_floor_note_names_the_declared_version_the_floor_and_the_key_to_write() {
        assertThat(GroovyResolver.floorNote("4.0.28"))
                .startsWith("groovy 4.0.28 is below jk's floor 5.0")
                .contains("compiles and runs with " + GroovyResolver.DEFAULT_VERSION)
                .contains("groovy = \"" + GroovyResolver.DEFAULT_VERSION + "\"");
    }

    @Test
    void default_version_is_a_groovy_5_release() {
        assertThat(GroovyResolver.DEFAULT_VERSION).isEqualTo("5.0.4");
        assertThat(GroovyResolver.DEFAULT_VERSION).startsWith("5.");
    }
}
