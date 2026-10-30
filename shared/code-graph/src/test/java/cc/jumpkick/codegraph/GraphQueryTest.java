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
        CodeGraph g = ShopFixture.graph();
        String out = new GraphQuery(g).query("what does OrderService use", GraphQuery.Mode.BFS, 2, 2000, List.of());
        assertThat(out).startsWith("From OrderService: ").contains(" within 2 hops, breadth-first.");
        assertThat(out).contains("\nOrderService  src/main/java/com/acme/shop/OrderService.java:6\n");
        assertThat(out)
                .contains("\nOrderService -inherits-> BaseService  src/main/java/com/acme/shop/OrderService.java:");
        assertThat(out).contains("\nShop -references-> OrderService (inferred)  README.md:3\n");
        assertThat(out).doesNotContain("NODE ", "EDGE ", "[src=", "community", "EXTRACTED");
        assertThat(new GraphQuery(g).query("zzzz qqqq", GraphQuery.Mode.BFS, 2, 2000, List.of()))
                .isEqualTo(GraphQuery.NO_MATCH);
    }

    /**
     * The seeds first, then what the question's own words match, best first, then the rest of the
     * walk nearest first: when a budget cuts, it cuts what the question did not ask about.
     */
    @Test
    void nodesTheQuestionMatchesComeBeforeTheRestOfTheWalk() throws IOException {
        String out = new GraphQuery(ShopFixture.graph())
                .query("what does OrderService use", GraphQuery.Mode.BFS, 2, 2000, List.of());
        assertThat(out.lines().skip(2).takeWhile(l -> !l.contains(" -")).map(l -> l.split("  ")[0]))
                .containsExactly(
                        "OrderService",
                        "OrderService.java",
                        "OrderService.audit()",
                        "OrderService.total()",
                        "NOTE: totals are computed in cents to avoid rounding drift",
                        "Shop",
                        "Auditable",
                        "BaseService",
                        "PriceCalculator",
                        "Design",
                        "README.md",
                        "Auditable.java",
                        "Auditable.audit()",
                        "BaseService.java",
                        "BaseService.log()");
        assertThat(out).contains(": 15 symbols within 2 hops");
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
        CodeGraph g = ShopFixture.graph();
        assertThat(new GraphSearch(g).compounds(List.of("price", "calculator", "sum")))
                .containsExactly("pricecalculator", "sum");
        assertThat(new GraphQuery(g).query("where is the price calculator", GraphQuery.Mode.BFS, 1, 2000, List.of()))
                .startsWith("From PriceCalculator: ");
    }

    @Test
    void aTypeFollowedByItsMemberSeedsOnTheMember() throws IOException {
        CodeGraph g = ShopFixture.withCalls();
        assertThat(new GraphSearch(g).members(List.of("baseservice", "log")))
                .containsExactly("src_main_java_com_acme_shop_baseservice_baseservice_log");
        assertThat(new GraphQuery(g).query("what calls BaseService log", GraphQuery.Mode.BFS, 1, 2000, List.of()))
                .startsWith("From BaseService.log()")
                .contains(
                        "\nOrderService.audit() -calls-> BaseService.log()  src/main/java/com/acme/shop/OrderService.java:21");
    }

    @Test
    void callQuestionsFollowOnlyCallEdges() throws IOException {
        CodeGraph g = ShopFixture.withCalls();
        String out = new GraphQuery(g).query("who calls log", GraphQuery.Mode.BFS, 2, 2000, List.of());
        assertThat(out).contains(", following call edges (read from the question).");
        assertThat(out)
                .contains(
                        "\nOrderService.audit() -calls-> BaseService.log()  src/main/java/com/acme/shop/OrderService.java:21");
        assertThat(out).doesNotContain("-contains->");
    }

    @Test
    void budgetCutsAtALineAndSaysHowMuchIsMissing() {
        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < 100; i++) nodes.add("n" + i + "  x.java:" + i);
        String out = Budget.cut(nodes, List.of(), 1, 100);
        assertThat(out).startsWith("[!] TRUNCATED: showing ").contains(" of 100 symbols");
        assertThat(out).contains("\nn0  x.java:0\n");
        assertThat(out).doesNotContain("n99  ");
    }

    @Test
    void edgesPastTheBudgetAreCutOnceEveryNodeFits() {
        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) nodes.add("n" + i + "  x.java:" + i);
        List<String> edges = new ArrayList<>();
        for (int i = 0; i < 100; i++) edges.add("n0 -calls-> n" + (i % 5) + "  x.java:" + (1000 + i));
        String out = Budget.cut(nodes, edges, 1, 300);
        assertThat(out)
                .startsWith("[i] All 5 symbols; ")
                .contains(" of 100 connections shown")
                .endsWith(" more connections cut");
        assertThat(out).contains("\nn4  x.java:4").contains("x.java:1000").doesNotContain("x.java:1099");
        assertThat(out.length()).isLessThan(300 * Budget.CHARS_PER_TOKEN + 300);
    }

    @Test
    void pathFollowsDirectionAndNeighborsListConnections() throws IOException {
        GraphTools tools = new GraphTools(ShopFixture.graph());
        assertThat(tools.shortestPath("OrderService", "BaseService", 8, false))
                .isEqualTo("Shortest path, 1 hop:\n"
                        + "  OrderService  src/main/java/com/acme/shop/OrderService.java:6\n"
                        + "  -inherits-> BaseService  src/main/java/com/acme/shop/BaseService.java:3");
        assertThat(tools.shortestPath("BaseService", "OrderService", 8, false)).startsWith("No directed path");
        assertThat(tools.neighbors("OrderService", "", 2000))
                .startsWith("Neighbors of OrderService:\n")
                .contains("\n  -inherits-> BaseService  src/main/java/com/acme/shop/OrderService.java:")
                .doesNotContain("[", "-->", "EXTRACTED");
        assertThat(tools.neighbors(".audit()", "", 2000))
                .startsWith("'.audit()' matches 2 nodes; pass one id:\n")
                .contains("  src/main/java/com/acme/shop/");
    }
}
