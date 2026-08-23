// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskNames;
import org.junit.jupiter.api.Test;

class HeavyActionPolicyTest {

    @Test
    void generations_by_task_kind() {
        assertThat(HeavyActionPolicy.generations(TaskNames.NATIVE_IMAGE + "@a"))
                .isEqualTo(HeavyActionPolicy.NATIVE_GENERATIONS);
        assertThat(HeavyActionPolicy.generations(TaskNames.WRITE_IMAGE + "@a"))
                .isEqualTo(HeavyActionPolicy.IMAGE_GENERATIONS);
        assertThat(HeavyActionPolicy.generations(TaskNames.PACKAGE_ASSEMBLY + "@a"))
                .isEqualTo(HeavyActionPolicy.ASSEMBLY_GENERATIONS);
        assertThat(HeavyActionPolicy.generations(TaskNames.PACKAGE_MINIFIED + "@a"))
                .isEqualTo(HeavyActionPolicy.ASSEMBLY_GENERATIONS);
        assertThat(HeavyActionPolicy.generations("compile-main@z")).isEqualTo(Integer.MAX_VALUE);
    }
}
