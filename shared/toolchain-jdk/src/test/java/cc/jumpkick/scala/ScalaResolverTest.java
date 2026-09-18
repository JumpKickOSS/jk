// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scala;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The floor that is Scala's alone: a Scala 2 version is not a refusal but the bundled Scala 3,
 * the compiler jk drives for it, with one sentence saying so.
 */
class ScalaResolverTest {

    @Test
    void a_version_below_the_scala_3_floor_compiles_with_the_default() {
        // Below the floor: Zinc's published bridge exists for Scala 3 compilers only.
        for (String tooOld : List.of("2.13.16", "2.12.20", "2.11.12", "3.0.0-RC1")) {
            assertThat(ScalaResolver.belowFloor(tooOld)).as(tooOld).isTrue();
            assertThat(ScalaResolver.floored(tooOld)).as(tooOld).isEqualTo(ScalaResolver.DEFAULT_VERSION);
        }
        // At or above the floor: the declared version, later patches, minors and majors included.
        for (String ok : List.of("3.0.0", "3.3.6", "3.8.4", "4.0.0")) {
            assertThat(ScalaResolver.belowFloor(ok)).as(ok).isFalse();
            assertThat(ScalaResolver.floored(ok)).as(ok).isEqualTo(ok);
        }
    }

    @Test
    void the_floor_note_names_the_declared_version_the_floor_and_the_key_to_write() {
        assertThat(ScalaResolver.floorNote("2.13.16"))
                .startsWith("scala 2.13.16 is below jk's floor 3.0")
                .contains("compiles with " + ScalaResolver.DEFAULT_VERSION)
                .contains("scala = \"" + ScalaResolver.DEFAULT_VERSION + "\"");
    }

    @Test
    void default_version_is_latest_stable_scala_3() {
        assertThat(ScalaResolver.DEFAULT_VERSION).isEqualTo("3.8.4");
        assertThat(ScalaResolver.DEFAULT_VERSION).startsWith("3.");
    }

    @Test
    void worker_modules_are_compiler_and_bridge_not_the_runtime_library() {
        assertThat(ScalaResolver.workerModules())
                .containsExactly(ScalaResolver.COMPILER_MODULE, ScalaResolver.BRIDGE_MODULE);
        assertThat(ScalaResolver.workerModules()).doesNotContain("org.scala-lang:scala3-library_3");
        assertThat(ScalaResolver.workerModules()).doesNotContain("org.scala-lang:scala-library");
    }
}
