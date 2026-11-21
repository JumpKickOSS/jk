// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AffectedTestsTest {

    private static AffectedTests ranking(String... classNames) {
        List<AffectedTests.Row> rows = new ArrayList<>();
        for (String name : classNames) rows.add(new AffectedTests.Row("com.acme:api", name, "test-src", 110));
        return AffectedTests.ranked(List.of(), List.of(), rows, rows.size());
    }

    @Test
    void identity_token_tells_apart_sets_whose_string_hashes_collide() {
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        assertThat(ranking("Aa").identityToken()).isNotEqualTo(ranking("BB").identityToken());
    }

    @Test
    void identity_token_ignores_rank_order_and_is_empty_for_nothing_ranked() {
        assertThat(ranking("b.BTest", "a.ATest").identityToken())
                .isEqualTo(ranking("a.ATest", "b.BTest").identityToken())
                .hasSize(64);
        assertThat(ranking().identityToken()).isEmpty();
    }
}
