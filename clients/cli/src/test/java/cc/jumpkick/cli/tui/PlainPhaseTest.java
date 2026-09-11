// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildStage;
import org.junit.jupiter.api.Test;

class PlainPhaseTest {

    @Test
    void maps_wire_stages_to_gerunds() {
        assertThat(PlainPhase.status("test")).isEqualTo("running tests");
        assertThat(PlainPhase.status("compile")).isEqualTo("compiling");
        assertThat(PlainPhase.status("native")).isEqualTo("native compiling");
        assertThat(PlainPhase.status("package")).isEqualTo("packaging");
        assertThat(PlainPhase.status("resolve")).isEqualTo("resolving");
        assertThat(PlainPhase.status("generate")).isEqualTo("generating");
        assertThat(PlainPhase.status("image")).isEqualTo("building image");
        assertThat(PlainPhase.status("publish")).isEqualTo("publishing");
        assertThat(PlainPhase.status("train")).isEqualTo("training");
    }

    @Test
    void maps_step_keys_through_build_stage() {
        assertThat(PlainPhase.status("compile-java")).isEqualTo("compiling");
        assertThat(PlainPhase.status("run-tests")).isEqualTo("running tests");
        assertThat(PlainPhase.status("native-image")).isEqualTo("native compiling");
        assertThat(PlainPhase.status("package-jar")).isEqualTo("packaging");
    }

    @Test
    // The null step is deliberate: an unknown step maps to the prepare phase.
    @SuppressWarnings("NullAway")
    void unknown_is_prepare() {
        assertThat(PlainPhase.status((String) null)).isEqualTo(PlainPhase.PREPARE);
        assertThat(PlainPhase.status("")).isEqualTo(PlainPhase.PREPARE);
        assertThat(PlainPhase.status("fmt")).isEqualTo(PlainPhase.PREPARE);
        assertThat(PlainPhase.status(BuildStage.OTHER)).isEqualTo(PlainPhase.PREPARE);
    }

    @Test
    void parses_compile_source_counts_and_remaining_tests() {
        assertThat(PlainPhase.compileSourcesStatus("compiling 12 sources")).contains("compiling 12 sources");
        assertThat(PlainPhase.compileSourcesStatus("compiling 12 Kotlin sources"))
                .contains("compiling 12 Kotlin sources");
        assertThat(PlainPhase.compileSourcesStatus("cc.jumpkick:jk-cli :: compiling 12 sources"))
                .contains("compiling 12 sources");
        assertThat(PlainPhase.runningTestsCount("running 80 tests")).contains(80);
        assertThat(PlainPhase.runningTestsCount("cc.jumpkick:jk-cli :: running 80 tests"))
                .contains(80);
        assertThat(PlainPhase.sameFamily("running 80 tests", "running 50 tests"))
                .isTrue();
        assertThat(PlainPhase.sameFamily("compiling 12 sources", "compiling 8 sources"))
                .isTrue();
        assertThat(PlainPhase.sameFamily("running 80 tests", "compiling 12 sources"))
                .isFalse();
    }
}
