// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RunMeasuresTest {

    @Test
    void class_walls_recorded_under_one_spelling_are_read_and_taken_under_another() {
        RunMeasures measures = new RunMeasures();
        measures.classWalls("C:\\Users\\me\\ws\\server\\engine", Map.of("a.BTest", 1200L));

        assertThat(measures.classWalls("c:/Users/me/ws/server/engine")).containsEntry("a.BTest", 1200L);
        assertThat(measures.takeClassWalls("C:/Users/me/ws/server/engine")).containsEntry("a.BTest", 1200L);
        assertThat(measures.classWalls("C:\\Users\\me\\ws\\server\\engine")).isEmpty();
    }

    @Test
    void a_runner_count_recorded_under_the_recorders_spelling_is_taken_under_the_journals() {
        RunMeasures measures = new RunMeasures();
        measures.runners("C:\\Users\\me\\ws\\server\\engine", 8);

        assertThat(measures.takeRunners("c:/Users/me/ws/server/engine")).isEqualTo(8);
        assertThat(measures.takeRunners("C:\\Users\\me\\ws\\server\\engine")).isZero();
    }

    @Test
    void taking_moves_everything_and_leaves_the_measures_empty() {
        RunMeasures measures = new RunMeasures();
        measures.classWalls("/ws/app", Map.of("a.ATest", 300L));
        measures.runners("/ws/app", 4);
        measures.startedWorker();

        RunMeasures taken = measures.take();

        assertThat(measures.isEmpty()).isTrue();
        assertThat(taken.coldWorker()).isTrue();
        assertThat(taken.takeRunners("/ws/app")).isEqualTo(4);
        assertThat(taken.takeClassWalls("/ws/app")).containsEntry("a.ATest", 300L);
    }

    @Test
    void native_input_bytes_are_the_runs_own_and_taken_once_under_any_spelling() {
        RunMeasures mine = new RunMeasures();
        RunMeasures other = new RunMeasures();
        mine.nativeInputBytes("C:\\Users\\me\\ws\\app", 64L << 20);
        mine.nativeInputBytes("C:\\Users\\me\\ws\\tiny", 512);

        assertThat(other.takeNativeInputBytes("c:/Users/me/ws/app"))
                .as("another run's")
                .isZero();
        assertThat(mine.takeNativeInputBytes("c:/Users/me/ws/tiny"))
                .as("under a KiB")
                .isZero();
        assertThat(mine.takeNativeInputBytes("c:/Users/me/ws/app")).isEqualTo(64L << 20);
        assertThat(mine.takeNativeInputBytes("C:\\Users\\me\\ws\\app")).isZero();
    }
}
