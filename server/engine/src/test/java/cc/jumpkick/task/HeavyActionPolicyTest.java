// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void a_generation_list_round_trips_and_skips_lines_that_do_not_read(@TempDir Path dir) throws IOException {
        Path gens = HeavyActionPolicy.gensFile(dir, "native-image@t");
        assertThat(HeavyActionPolicy.readGenerations(gens)).isEmpty();
        List<HeavyActionPolicy.Generation> written = List.of(
                new HeavyActionPolicy.Generation("checkout-a", "key-1"),
                new HeavyActionPolicy.Generation("checkout-b", "key-2"));
        HeavyActionPolicy.writeGenerations(gens, written);
        assertThat(Files.readString(gens)).isEqualTo("checkout-a key-1\ncheckout-b key-2\n");
        assertThat(HeavyActionPolicy.readGenerations(gens)).isEqualTo(written);

        Files.writeString(gens, "bare-key\n\ncheckout-a key-1\n");
        assertThat(HeavyActionPolicy.readGenerations(gens)).containsExactly(written.get(0));

        HeavyActionPolicy.writeGenerations(gens, List.of());
        assertThat(gens).doesNotExist();
        assertThat(HeavyActionPolicy.gensLock(gens)).hasFileName("native-image@t.gens.lock");
    }
}
