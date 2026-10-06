// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ParallelMapTest {

    @Test
    void results_keep_the_input_order_on_both_sides_of_the_threshold() throws IOException {
        for (int n : new int[] {3, ParallelMap.THRESHOLD * 20}) {
            List<Integer> in = IntStream.range(0, n).boxed().toList();
            assertThat(ParallelMap.map(in, i -> i * 2))
                    .isEqualTo(in.stream().map(i -> i * 2).toList());
        }
    }

    @Test
    void an_io_failure_is_rethrown_as_itself() {
        List<Integer> in = IntStream.range(0, ParallelMap.THRESHOLD * 4).boxed().toList();
        assertThatThrownBy(() -> ParallelMap.map(in, i -> {
                    if (i == 100) throw new IOException("item 100");
                    return i;
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("item 100");
    }

    @Test
    void a_null_result_is_kept_in_place() throws IOException {
        List<Integer> in = IntStream.range(0, ParallelMap.THRESHOLD * 2).boxed().toList();
        List<@Nullable String> out = ParallelMap.<Integer, @Nullable String>map(in, i -> i % 2 == 0 ? null : "odd");
        assertThat(out).hasSize(in.size());
        assertThat(out.get(0)).isNull();
        assertThat(out.get(1)).isEqualTo("odd");
    }
}
