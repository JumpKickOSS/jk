// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.GraphQuery;
import cc.jumpkick.codegraph.Scope;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class VocabularyTest {

    @Test
    void aDocCommentsFirstSentenceIsKeptWithInlineTagsReduced() {
        Fragment f = Objects.requireNonNull(Extractors.forPath("src/main/java/a/Rates.java"))
                .extract("src/main/java/a/Rates.java", """
                        package a;

                        /**
                         * Looks up the {@code Tariff} for a <b>route</b>. Cached per day.
                         *
                         * @since 2
                         */
                        public class Rates {
                            /** Today's rate card. */
                            public long card() {
                                return 1;
                            }

                            // not a doc comment
                            void plain() {}
                        }
                        """);
        GraphNode rates = f.nodes().stream()
                .filter(n -> n.label().equals("Rates"))
                .findFirst()
                .orElseThrow();
        assertThat(Decl.doc(rates)).isEqualTo("Looks up the Tariff for a route.");
        GraphNode card = f.nodes().stream()
                .filter(n -> n.label().equals(".card()"))
                .findFirst()
                .orElseThrow();
        assertThat(Decl.doc(card)).isEqualTo("Today's rate card.");
        GraphNode plain = f.nodes().stream()
                .filter(n -> n.label().equals(".plain()"))
                .findFirst()
                .orElseThrow();
        assertThat(Decl.doc(plain)).isNull();
    }

    @Test
    void docWordsSeedAQuestionThatNamesNoSymbol() throws IOException {
        CodeGraph shop = ExtractorsTest.shop();
        assertThat(new GraphQuery(shop).query("which code places things", GraphQuery.Mode.BFS, 1, 2000, List.of()))
                .startsWith("From OrderService: ");
    }

    @Test
    void anAliasSeedsItsTerm() throws IOException {
        CodeGraph shop = ExtractorsTest.shop();
        GraphQuery q = new GraphQuery(shop, Scope.ALL, Map.of("PriceCalculator", List.of("tariff", "rate card")));
        assertThat(q.query("where is the rate card", GraphQuery.Mode.BFS, 1, 2000, List.of()))
                .startsWith("From PriceCalculator: ");
    }

    @Test
    void aMissNamesTheClosestNames() throws IOException {
        CodeGraph shop = ExtractorsTest.shop();
        String out = new GraphQuery(shop).query("PriceCalculater", GraphQuery.Mode.BFS, 1, 2000, List.of());
        assertThat(out).startsWith("No matching nodes found. Closest names:").contains("PriceCalculator");
    }
}
