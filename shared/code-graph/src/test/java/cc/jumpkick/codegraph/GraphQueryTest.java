// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphQueryTest {

    @Test
    void querySeedsOnTheNamedSymbolAndRendersItsNeighbourhood() throws IOException {
        CodeGraph g = GraphJsonTest.shop();
        String out = new GraphQuery(g).query("what does OrderService use", GraphQuery.Mode.BFS, 2, 2000, List.of());
        assertThat(out).startsWith("Traversal: BFS depth=2 | Start: [OrderService]");
        assertThat(out).contains("NODE OrderService [src=src/main/java/com/acme/shop/OrderService.java loc=L6");
        assertThat(out).contains("EDGE OrderService --inherits [EXTRACTED]--> BaseService");
        assertThat(new GraphQuery(g).query("zzzz qqqq", GraphQuery.Mode.BFS, 2, 2000, List.of()))
                .isEqualTo(GraphQuery.NO_MATCH);
    }

    /**
     * The seeds first, then what the question's own words match, best first, then the rest of the
     * walk nearest first: when a budget cuts, it cuts what the question did not ask about.
     */
    @Test
    void nodesTheQuestionMatchesComeBeforeTheRestOfTheWalk() throws IOException {
        String out = new GraphQuery(GraphJsonTest.shop())
                .query("what does OrderService use", GraphQuery.Mode.BFS, 2, 2000, List.of());
        assertThat(out.lines().filter(l -> l.startsWith("NODE ")).map(l -> l.substring(5, l.indexOf(" ["))))
                .containsExactly(
                        "OrderService",
                        "OrderService.java",
                        "OrderService.audit()",
                        "OrderService.total()",
                        "Shop",
                        "PriceCalculator",
                        "Auditable",
                        "BaseService",
                        "Item",
                        "PriceCalculator.sum()",
                        "README.md",
                        "BaseService.log()",
                        "PriceCalculator.kt",
                        "list",
                        "Override",
                        "Design",
                        "Auditable.java",
                        "Auditable.audit()",
                        "BaseService.java");
        assertThat(out).contains("19 nodes found");
    }

    @Test
    void wholeWordsInsideNamesAndTheirInflectionsSeed() {
        assertThat(GraphSearch.stem("errors")).isEqualTo("error");
        assertThat(GraphSearch.stem("queries")).isEqualTo("query");
        assertThat(GraphSearch.stem("booked")).isEqualTo("book");
        assertThat(GraphSearch.stem("class")).isEqualTo("class");
        assertThat(GraphSearch.sameWord("available", "availability")).isTrue();
        assertThat(GraphSearch.sameWord("cancel", "cancellation")).isTrue();
        assertThat(GraphSearch.sameWord("order", "ordinal")).isFalse();
        assertThat(GraphSearch.queryTerms("which endpoint returns rooms"))
                .contains("endpoint", "controller", "mapping");
    }

    @Test
    void adjacentWordsSpellingASymbolSeedOnThatSymbol() throws IOException {
        CodeGraph g = GraphJsonTest.shop();
        assertThat(new GraphSearch(g).compounds(List.of("price", "calculator", "sum")))
                .containsExactly("pricecalculator", "sum");
        assertThat(new GraphQuery(g).query("where is the price calculator", GraphQuery.Mode.BFS, 1, 2000, List.of()))
                .contains("Start: [PriceCalculator]");
    }

    @Test
    void aTypeFollowedByItsMemberSeedsOnTheMember() throws IOException {
        CodeGraph g = GraphJsonTest.shop();
        assertThat(new GraphSearch(g).members(List.of("baseservice", "log")))
                .containsExactly("src_main_java_com_acme_shop_baseservice_baseservice_log");
        assertThat(new GraphQuery(g).query("what calls BaseService log", GraphQuery.Mode.BFS, 1, 2000, List.of()))
                .contains("Start: [BaseService.log()")
                .contains("EDGE OrderService.audit() --calls [INFERRED context=call]--> BaseService.log()");
    }

    @Test
    void callQuestionsFollowOnlyCallEdges() throws IOException {
        CodeGraph g = GraphJsonTest.shop();
        String out = new GraphQuery(g).query("who calls log", GraphQuery.Mode.BFS, 2, 2000, List.of());
        assertThat(out).contains("Context: call (heuristic)");
        assertThat(out).contains("EDGE OrderService.audit() --calls [INFERRED context=call]--> BaseService.log()");
        assertThat(out).doesNotContain("--contains");
    }

    @Test
    void budgetCutsAtALineAndSaysHowMuchIsMissing() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) lines.add("NODE n" + i + " [src=x.java loc=L" + i + " community=0]");
        String out = Budget.cut(lines, 1, 100);
        assertThat(out).startsWith("[!] TRUNCATED: showing ");
        assertThat(out).contains("NODE n0 ");
        assertThat(out).doesNotContain("NODE n99 ");
    }

    @Test
    void edgesPastTheBudgetAreCutOnceEveryNodeFits() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) lines.add("NODE n" + i + " [src=x.java loc=L" + i + " community=0]");
        for (int i = 0; i < 100; i++) lines.add("EDGE n0 --calls [EXTRACTED]--> n" + (i % 5) + " at=x.java:L" + i);
        String out = Budget.cut(lines, 1, 300);
        assertThat(out)
                .startsWith("[i] All 5 nodes; ")
                .contains(" of 100 edges shown")
                .endsWith(" more edges cut");
        assertThat(out).contains("NODE n4 ").contains("at=x.java:L0").doesNotContain("at=x.java:L99");
        assertThat(out.length()).isLessThan(300 * Budget.CHARS_PER_TOKEN + 300);
    }

    @Test
    void pathFollowsDirectionAndExplainListsConnections() throws IOException {
        GraphTools tools = new GraphTools(GraphJsonTest.shop());
        assertThat(tools.shortestPath("OrderService", "BaseService", 8, false))
                .isEqualTo("Shortest path (1 hops):\n  OrderService --inherits [EXTRACTED]--> BaseService");
        assertThat(tools.shortestPath("BaseService", "OrderService", 8, false)).startsWith("No directed path");
        assertThat(tools.explain("OrderService", false))
                .contains("Node: OrderService")
                .contains("--> BaseService [inherits] [EXTRACTED]");
        assertThat(tools.node(".audit()")).startsWith("'.audit()' matches 2 nodes; pass one id:");
    }
}
