// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskNames;
import org.junit.jupiter.api.Test;

class HeavyActionPolicyTest {

    @Test
    void class_c_kinds_and_generations() {
        assertThat(HeavyActionPolicy.isClassC(TaskNames.NATIVE_IMAGE + "@abc")).isTrue();
        assertThat(HeavyActionPolicy.isClassC(TaskNames.WRITE_IMAGE + "@x")).isTrue();
        assertThat(HeavyActionPolicy.isClassC(TaskNames.PACKAGE_ASSEMBLY + "@y"))
                .isTrue();
        assertThat(HeavyActionPolicy.isClassC("compile-main@z")).isFalse();
        assertThat(HeavyActionPolicy.isClassC("package-jar@z")).isFalse();

        assertThat(HeavyActionPolicy.generations(TaskNames.NATIVE_IMAGE + "@a"))
                .isEqualTo(HeavyActionPolicy.NATIVE_GENERATIONS);
        assertThat(HeavyActionPolicy.generations(TaskNames.WRITE_IMAGE + "@a"))
                .isEqualTo(HeavyActionPolicy.IMAGE_GENERATIONS);
        assertThat(HeavyActionPolicy.generations(TaskNames.PACKAGE_ASSEMBLY + "@a"))
                .isEqualTo(HeavyActionPolicy.ASSEMBLY_GENERATIONS);
    }

    @Test
    void budget_is_half_of_cache() {
        assertThat(HeavyActionPolicy.classCBudgetBytes(4L * 1024 * 1024 * 1024)).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(HeavyActionPolicy.TTL.toDays()).isEqualTo(3);
    }
}
