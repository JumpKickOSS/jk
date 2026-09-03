// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskPhasesTest {

    @Test
    void maps_core_tasks() {
        assertThat(TaskPhases.of("parse-build")).isEqualTo(TaskPhases.RESOLVE);
        assertThat(TaskPhases.of("resolve-deps")).isEqualTo(TaskPhases.RESOLVE);
        assertThat(TaskPhases.of("compile-java")).isEqualTo(TaskPhases.COMPILE);
        assertThat(TaskPhases.of("run-tests")).isEqualTo(TaskPhases.TEST);
        assertThat(TaskPhases.of("package-jar")).isEqualTo(TaskPhases.PACKAGE);
        assertThat(TaskPhases.of("native-image")).isEqualTo(TaskPhases.NATIVE);
        assertThat(TaskPhases.of("write-image")).isEqualTo(TaskPhases.IMAGE);
    }

    @Test
    void plugin_and_unknown() {
        assertThat(TaskPhases.of("plugin-protoc")).isEqualTo(TaskPhases.COMPILE);
        assertThat(TaskPhases.of("plugin-spring-boot-package")).isEqualTo(TaskPhases.PACKAGE);
        assertThat(TaskPhases.of("")).isEqualTo(TaskPhases.OTHER);
        assertThat(TaskPhases.of(null)).isEqualTo(TaskPhases.OTHER);
    }

    /** Regression: stamps calibrate with their compiles — explicit AND fallback. */
    @Test
    void write_stamps_are_compile_phase_in_both_arms() {
        assertThat(TaskPhases.of("write-stamp")).isEqualTo(TaskPhases.COMPILE);
        assertThat(TaskPhases.of("write-stamp-kotlin")).isEqualTo(TaskPhases.COMPILE);
        // Fallback arm: a future language's stamp must not drift to PACKAGE.
        assertThat(TaskPhases.of("write-stamp-scala")).isEqualTo(TaskPhases.COMPILE);
    }

    /** Regression: metrics phase agrees with the task's wire group ("package"). */
    @Test
    void build_logic_before_package_files_under_package() {
        assertThat(TaskPhases.of("build-logic-before-package")).isEqualTo(TaskPhases.PACKAGE);
        assertThat(TaskPhases.of("build-logic-after-compile")).isEqualTo(TaskPhases.COMPILE);
    }
}
