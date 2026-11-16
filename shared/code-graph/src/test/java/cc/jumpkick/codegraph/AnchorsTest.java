// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * An anchor's id is {@code <kind>:<key>}, the key folding exactly the spellings the surface treats
 * as one; one anchor stands for a surface however many files define it; extractors grade their own
 * edges and the linker's are inferred; {@code callers} and {@code find} answer for an anchor.
 */
class AnchorsTest {

    @Test
    void eachKindKeysTheSpellingsItsSurfaceTreatsAsOne() {
        assertThat(Anchors.id("config", "shop.maxItems"))
                .isEqualTo(Anchors.id("config", "shop.max-items"))
                .isEqualTo(Anchors.id("config", "SHOP.MAX_ITEMS"))
                .isEqualTo("config:shop.maxitems");
        assertThat(Anchors.display("config", "shop.maxItems")).isEqualTo("shop.max-items");
        assertThat(Anchors.id("env", "DATABASE_URL"))
                .isEqualTo("env:DATABASE_URL")
                .isNotEqualTo(Anchors.id("env", "database_url"));
        assertThat(List.of(
                        "get /orders/{id}", "GET orders/{orderId:\\d+}/", "GET //orders/:id?x=1", "GET /orders/<id>"))
                .extracting(r -> Anchors.id("route", r))
                .containsOnly("route:GET /orders/{}");
        assertThat(Anchors.id("route", "/health")).isEqualTo("route:* /health");
        assertThat(Anchors.id("route", "POST /")).isEqualTo("route:POST /");
        assertThat(Anchors.id("table", "Billing.ORDERS")).isEqualTo("table:billing.orders");
        assertThat(Anchors.id("table", "public.orders")).isEqualTo("table:orders");
        assertThat(Anchors.id("column", "dbo.orders.status")).isEqualTo("column:orders.status");
        assertThat(Anchors.id("table", "\"Orders\"")).isEqualTo("table:Orders");
        assertThat(Anchors.id("column", "`orders`.[Status]")).isEqualTo("column:orders.Status");
        assertThat(Anchors.id("column", "orders.STATUS")).isEqualTo("column:orders.status");
        assertThat(Anchors.id("cli", "jk  build   --out=dist")).isEqualTo("cli:jk build --out");
        assertThat(Anchors.id("ci.job", "  build  "))
                .as("an unlisted kind keys its name trimmed")
                .isEqualTo("ci.job:build");
        assertThatThrownBy(() -> Anchors.id("Java", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAListedKindWrittenWithItsColonParsesAsAnAnchor() {
        assertThat(Anchors.parse("route:GET /orders/{id}")).isEqualTo("route:GET /orders/{}");
        assertThat(Anchors.parse("config:server.port")).isEqualTo("config:server.port");
        assertThat(Anchors.parse("OrderService:total")).isNull();
        assertThat(Anchors.parse("src/main/App.java:12")).isNull();
        assertThat(Anchors.parse("ci.job:build")).isNull();
        assertThat(Anchors.parse("route:")).isNull();
    }

    private static Fragment extract(String rel, String text) {
        Extractor x = Objects.requireNonNull(Extractors.forPath(rel));
        return x.extract(rel, text);
    }

    /** A Spring app: a key set in two profile files, read by an annotation, a literal and another file. */
    private static CodeGraph app() {
        List<Fragment> fragments = new ArrayList<>();
        fragments.add(extract("src/main/resources/application.yml", "shop:\n  max-items: 10\n  currency: EUR\n"));
        fragments.add(extract("src/main/resources/application-prod.properties", "shop.maxItems=50\n"));
        fragments.add(extract("src/test/resources/application.properties", "shop.currency=USD\n"));
        fragments.add(extract("deploy/notes.properties", "greeting=${shop.currency} ${never.set}\n"));
        String cart = "src/main/java/com/acme/Cart.java";
        String cartId = GraphIds.declared(cart, "Cart");
        String limit = GraphIds.member(cartId, "limit");
        String report = GraphIds.member(cartId, "report");
        fragments.add(new Fragment(
                List.of(
                        new GraphNode(cartId, "Cart", "code", cart, "L3"),
                        new GraphNode(limit, ".limit()", "code", cart, "L7"),
                        new GraphNode(report, ".report()", "code", cart, "L12"),
                        Anchors.node("config", "shop.maxItems")),
                List.of(
                        GraphEdge.of(cartId, limit, Relations.METHOD, Confidence.EXTRACTED, cart, 7),
                        GraphEdge.of(cartId, report, Relations.METHOD, Confidence.EXTRACTED, cart, 12),
                        Anchors.edge(limit, "config", "shop.maxItems", Relations.USES, Confidence.DECLARED, cart, 0)),
                List.of(
                        new Mention(report, Mention.Kind.CONFIG_KEY, "shop.currency", 13),
                        new Mention(report, Mention.Kind.CONFIG_KEY, "os.name", 14))));
        CodeGraph g = Linker.link(fragments);
        g.modules(new Modules(List.of(new Modules.Module("", false))));
        return g;
    }

    @Test
    void aKeySetInSeveralFilesIsOneAnchorThatEveryReaderLinksTo() {
        CodeGraph g = app();
        GraphNode key = Objects.requireNonNull(g.node("config:shop.maxitems"));
        assertThat(key.label())
                .as("the kebab spelling, whichever fragment came first")
                .isEqualTo("shop.max-items");
        assertThat(key.fileType()).isEqualTo(Anchors.FILE_TYPE);
        assertThat(g.incident(key.id()))
                .extracting(GraphEdge::sourceFile, GraphEdge::relation, GraphEdge::confidence, GraphEdge::context)
                .containsExactlyInAnyOrder(
                        tuple("src/main/resources/application.yml", Relations.DEFINES, Confidence.DECLARED, null),
                        tuple(
                                "src/main/resources/application-prod.properties",
                                Relations.DEFINES,
                                Confidence.DECLARED,
                                "shop.maxItems"),
                        tuple(
                                "src/main/java/com/acme/Cart.java",
                                Relations.USES,
                                Confidence.DECLARED,
                                "shop.maxItems"));
        assertThat(g.incident("config:shop.currency"))
                .filteredOn(e -> e.relation().equals(Relations.USES))
                .extracting(GraphEdge::sourceFile, GraphEdge::confidence)
                .as("a key-shaped literal and a placeholder in a file are matched names, so inferred")
                .containsExactlyInAnyOrder(
                        tuple("src/main/java/com/acme/Cart.java", Confidence.INFERRED),
                        tuple("deploy/notes.properties", Confidence.INFERRED));
        assertThat(g.has("config:os.name"))
                .as("a literal that names no anchor makes none")
                .isFalse();
        assertThat(g.has("config:never.set"))
                .as("an inferred mention never makes an anchor")
                .isFalse();
    }

    @Test
    void callersOfAnAnchorListsDefinitionsThenUsesByPlaceWithGradeAndSpelling() {
        String answer = new GraphTools(g()).callers("config:shop.currency", 1, 2000);
        assertThat(answer)
                .startsWith("config:shop.currency: 2 definitions, 2 uses (2 inferred).")
                .contains(
                        "defined:\n  src/main/resources/application.yml:3\n  src/test/resources/application.properties:1\n",
                        "main:\n  Cart.report()  src/main/java/com/acme/Cart.java:13 (inferred)\n",
                        "other:\n  deploy/notes.properties:1 (inferred)\n",
                        "An inferred use was matched by text");
        String byLabel = new GraphTools(g()).callers("shop.max-items", 1, 2000);
        assertThat(byLabel)
                .as("a label only an anchor has answers for the anchor")
                .startsWith("config:shop.maxitems (shop.max-items): 2 definitions, 1 use (1 declared).")
                .endsWith("main:\n  Cart.limit()  src/main/java/com/acme/Cart.java:7  as shop.maxItems")
                .doesNotContain("(inferred)", "matched by text");
        assertThat(new GraphTools(g()).anchorUses("route:GET /nowhere", 2000))
                .isEqualTo("Nothing in the graph defines or uses route:GET /nowhere.");
        assertThat(new GraphTools(g()).anchorUses("Cart", 2000)).isNull();
    }

    private static CodeGraph g() {
        return app();
    }

    @Test
    void findListsAKindsAnchorsWithTheirCounts() {
        String found = SymbolFinder.of(Map.of("kind", "config"), "shop.*").find(app(), Scope.ALL, 2000);
        assertThat(found).isEqualTo("""
                        2 matches
                        config  config:shop.currency  2 definitions, 2 uses  src/main/resources/application.yml:3
                        config  config:shop.maxitems (shop.max-items)  2 definitions, 1 use  \
                        src/main/resources/application-prod.properties:1""");
        assertThat(SymbolFinder.of(Map.of("kind", "class"), null).find(app(), Scope.ALL, 2000))
                .doesNotContain("config:");
    }

    /**
     * Nothing in an anchor's id or grade names the JVM: no descriptor, no class file path or internal
     * name, no Maven coordinate. A second language adds an extractor, not a schema change.
     */
    @Test
    void noAnchorIdOrGradeNamesTheJvm() {
        CodeGraph g = app();
        List<String> ids =
                g.nodes().stream().filter(Anchors::is).map(GraphNode::id).toList();
        assertThat(ids).isNotEmpty();
        assertJvmFree(ids);
        assertThat(g.edges().stream()
                        .filter(e -> Anchors.is(g.node(e.target())))
                        .map(e -> e.confidence().grade())
                        .distinct())
                .isSubsetOf("bound", "declared", "inferred");
    }

    /** Shared with the compiled-classes test in the graph worker. */
    static void assertJvmFree(List<String> anchorIds) {
        for (String id : anchorIds) {
            assertThat(id)
                    .matches("[a-z][a-z0-9.-]*:.+")
                    .doesNotContainPattern("\\(L?[\\w/$;\\[]*\\)[VZBCSIJFDL\\[]")
                    .doesNotContainPattern("L[a-z]\\w*/[\\w/$]+;")
                    .doesNotContainPattern("\\.class\\b")
                    .doesNotContainPattern("\\b[\\w.-]+:[\\w.-]+:\\d[\\w.-]*");
            String kind = Objects.requireNonNull(Anchors.kindOf(id));
            assertThat(kind).isNotIn("java", "jvm", "class", "maven", "kotlin", "groovy");
        }
    }
}
