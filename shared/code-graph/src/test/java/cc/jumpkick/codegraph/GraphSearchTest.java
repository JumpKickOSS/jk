// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class GraphSearchTest {

    /** Every search of one graph reads the same folded text, and a term's IDF is counted once. */
    @Test
    void searchesOfOneGraphShareTheFoldedTextAndEachTermsIdf() throws IOException {
        CodeGraph g = ShopFixture.graph();
        new GraphSearch(g).rank("order service total");
        int passes = g.searchIndex().dfPasses();
        assertThat(passes).isEqualTo(3);

        new GraphSearch(g, Scope.ALL).rank("order service total");
        new GraphSearch(g).nearest("order service", 3);
        assertThat(g.searchIndex()).isSameAs(g.searchIndex());
        assertThat(g.searchIndex().dfPasses()).isEqualTo(passes);
    }

    /** Seeding and ranking one question make one scoring pass over the nodes, not two. */
    @Test
    void seedsAndRankOfOneQuestionScoreTheNodesOnce() throws IOException {
        GraphSearch search = new GraphSearch(ShopFixture.graph());
        assertThat(search.seeds("order total")).isNotEmpty();
        assertThat(search.rank("order total")).isNotEmpty();
        assertThat(search.scoringPasses()).isEqualTo(1);
    }
}
