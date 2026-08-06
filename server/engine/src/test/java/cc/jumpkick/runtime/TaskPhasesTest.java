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
}
