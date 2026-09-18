// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The options a mixed job hands scalac: the Java level, and messages as plain text. */
class ScalaBridgeOptionsTest {

    private static final ScalaBridge.MixedScala MIXED =
            new ScalaBridge.MixedScala("3.9.0", List.of(Path.of("scala3-compiler_3-3.9.0.jar")), null, null, null);

    @Test
    void scalac_runs_without_colour_so_a_diagnostic_is_text() {
        assertThat(ScalaBridge.scalacOptions(MIXED, 25)).containsExactly("-java-output-version", "25", "-color:never");
        assertThat(ScalaBridge.scalacOptions(MIXED, 0)).containsExactly("-color:never");
    }

    @Test
    void a_java_only_job_hands_scalac_nothing() {
        assertThat(ScalaBridge.scalacOptions(null, 25)).isEmpty();
    }
}
