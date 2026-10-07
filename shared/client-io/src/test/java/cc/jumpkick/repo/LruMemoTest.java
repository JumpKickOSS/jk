// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LruMemoTest {

    @Test
    void past_the_weight_the_least_recently_used_entry_goes_first() {
        LruMemo<String, String> memo = new LruMemo<>(3, v -> 1);
        memo.put("a", "A");
        memo.put("b", "B");
        memo.put("c", "C");
        assertThat(memo.get("a")).isEqualTo("A"); // a is now the most recent

        memo.put("d", "D");

        assertThat(memo.get("b")).as("the least recently used").isNull();
        assertThat(memo.get("a")).isEqualTo("A");
        assertThat(memo.get("c")).isEqualTo("C");
        assertThat(memo.get("d")).isEqualTo("D");
        assertThat(memo.weight()).isEqualTo(3);
    }

    @Test
    void a_heavy_value_evicts_as_many_light_ones_as_it_weighs() {
        LruMemo<String, Integer> memo = new LruMemo<>(10, v -> v);
        memo.put("a", 4);
        memo.put("b", 4);
        memo.put("c", 6);

        assertThat(memo.get("a")).isNull();
        assertThat(memo.get("b")).isEqualTo(4);
        assertThat(memo.get("c")).isEqualTo(6);
        assertThat(memo.weight()).isEqualTo(10);
    }

    @Test
    void a_value_heavier_than_the_bound_is_kept_alone() {
        LruMemo<String, Integer> memo = new LruMemo<>(5, v -> v);
        memo.put("a", 2);
        memo.put("big", 9);

        assertThat(memo.get("a")).isNull();
        assertThat(memo.get("big")).isEqualTo(9);
        assertThat(memo.size()).isEqualTo(1);
    }

    @Test
    void replacing_a_value_reprices_it_and_clear_counts_what_went() {
        LruMemo<String, Integer> memo = new LruMemo<>(100, v -> v);
        memo.put("a", 10);
        memo.put("a", 3);
        memo.put("b", 5);

        assertThat(memo.weight()).isEqualTo(8);
        assertThat(memo.clear()).isEqualTo(2);
        assertThat(memo.weight()).isZero();
    }
}
