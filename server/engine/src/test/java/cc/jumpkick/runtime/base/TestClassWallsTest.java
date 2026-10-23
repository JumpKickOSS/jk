// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TestClassWallsTest {

    @Test
    void the_recorders_windows_path_and_the_journals_slashed_one_are_one_module() {
        TestClassWalls.put("C:\\Users\\me\\ws\\server\\engine", Map.of("a.BTest", 1200L));

        assertThat(TestClassWalls.get("c:/Users/me/ws/server/engine")).containsEntry("a.BTest", 1200L);
        assertThat(TestClassWalls.take("C:/Users/me/ws/server/engine")).containsEntry("a.BTest", 1200L);
        assertThat(TestClassWalls.get("C:\\Users\\me\\ws\\server\\engine"))
                .as("taken once")
                .isEmpty();
    }
}
