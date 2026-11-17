// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * HTTP routes: handlers and an OpenAPI document define them; tests, a front end, a script, a
 * workflow and a README use them; each use links only to a route it fits, the most specific one,
 * declared when a client call names the route's own method and template and inferred otherwise.
 */
class RouteAnchorsTest {

    private static final String CTRL = "src/main/java/com/acme/HotelController.java";
    private static final String TEST = "src/test/java/com/acme/HotelControllerTest.java";

    private static Fragment extract(String rel, String text) {
        Extractor x = Objects.requireNonNull(Extractors.forPath(rel));
        return x.extract(rel, text);
    }

    private static CodeGraph hotel() {
        String ctrl = GraphIds.declared(CTRL, "HotelController");
        String list = GraphIds.member(ctrl, "hotels");
        String one = GraphIds.member(ctrl, "hotel");
        String book = GraphIds.member(ctrl, "book");
        String spa = GraphIds.member(ctrl, "spa");
        String test = GraphIds.declared(TEST, "HotelControllerTest");
        String lists = GraphIds.member(test, "lists");
        String missing = GraphIds.member(test, "missing");
        List<Fragment> fragments = new ArrayList<>();
        fragments.add(new Fragment(
                List.of(
                        new GraphNode(ctrl, "HotelController", "code", CTRL, "L10"),
                        new GraphNode(list, ".hotels()", "code", CTRL, "L14"),
                        new GraphNode(one, ".hotel()", "code", CTRL, "L19"),
                        new GraphNode(book, ".book()", "code", CTRL, "L24"),
                        new GraphNode(spa, ".spa()", "code", CTRL, "L30"),
                        Anchors.node("route", "GET /api/hotels"),
                        Anchors.node("route", "GET /api/hotels/{slug}"),
                        Anchors.node("route", "POST /api/reservations"),
                        Anchors.node("route", "GET /hotels/**")),
                List.of(
                        GraphEdge.of(ctrl, list, Relations.METHOD, Confidence.EXTRACTED, CTRL, 14),
                        GraphEdge.of(ctrl, one, Relations.METHOD, Confidence.EXTRACTED, CTRL, 19),
                        GraphEdge.of(ctrl, book, Relations.METHOD, Confidence.EXTRACTED, CTRL, 24),
                        GraphEdge.of(ctrl, spa, Relations.METHOD, Confidence.EXTRACTED, CTRL, 30),
                        Anchors.edge(list, "route", "GET /api/hotels", Relations.DEFINES, Confidence.DECLARED, CTRL, 0),
                        Anchors.edge(
                                one,
                                "route",
                                "GET /api/hotels/{slug}",
                                Relations.DEFINES,
                                Confidence.DECLARED,
                                CTRL,
                                0),
                        Anchors.edge(
                                book,
                                "route",
                                "POST /api/reservations",
                                Relations.DEFINES,
                                Confidence.DECLARED,
                                CTRL,
                                0),
                        Anchors.edge(
                                spa, "route", "GET /hotels/**", Relations.DEFINES, Confidence.DECLARED, CTRL, 0))));
        fragments.add(new Fragment(
                List.of(
                        new GraphNode(test, "HotelControllerTest", "code", TEST, "L8"),
                        new GraphNode(lists, ".lists()", "code", TEST, "L12"),
                        new GraphNode(missing, ".missing()", "code", TEST, "L20")),
                List.of(
                        GraphEdge.of(test, lists, Relations.METHOD, Confidence.EXTRACTED, TEST, 12),
                        GraphEdge.of(test, missing, Relations.METHOD, Confidence.EXTRACTED, TEST, 20)),
                List.of(
                        new Mention(lists, Mention.Kind.ROUTE_CALL, "GET /api/hotels", 13),
                        new Mention(missing, Mention.Kind.ROUTE_CALL, "GET /api/hotels/nowhere", 21),
                        new Mention(missing, Mention.Kind.ROUTE, "* /tmp/work", 22))));
        fragments.add(extract("frontend/src/app/hotel-api.ts", """
                export class HotelApi {
                  hotels() {
                    return this.http.get<Hotel[]>('/api/hotels');
                  }
                  hotel(slug: string) {
                    return this.http.get<Hotel>(`/api/hotels/${encodeURIComponent(slug)}`);
                  }
                  book(r: Booking) {
                    return fetch('/api/reservations', { method: 'POST', body: JSON.stringify(r) });
                  }
                }
                """));
        fragments.add(extract("scripts/smoke.sh", """
                #!/bin/sh
                # curl http://localhost:8080/api/commented-out
                curl -fsS http://localhost:8080/api/hotels/harborview
                curl -fsS -d '{}' http://${HOST}:8080/api/reservations
                curl -fsS https://docs.example.com/api/hotels
                """));
        fragments.add(extract(".github/workflows/ci.yml", """
                jobs:
                  smoke:
                    steps:
                      - run: wget -qO- http://app:8080/api/hotels
                """));
        fragments.add(extract("README.md", """
                # Hotel

                ```bash
                curl -X POST http://localhost:8080/api/reservations
                ```
                """));
        fragments.add(extract("api/openapi.yaml", """
                openapi: 3.0.3
                paths:
                  /api/hotels/{id}:
                    get:
                      operationId: hotel
                    delete:
                      operationId: removeHotel
                """));
        CodeGraph g = Linker.link(fragments);
        g.modules(new Modules(List.of(new Modules.Module("", false), new Modules.Module("frontend/", false))));
        return g;
    }

    private static List<GraphEdge> uses(CodeGraph g, String route) {
        return g.incident(route).stream()
                .filter(e -> e.relation().equals(Relations.USES))
                .toList();
    }

    @Test
    void eachUseLinksToTheRouteItFitsWithItsGrade() {
        CodeGraph g = hotel();
        assertThat(uses(g, "route:GET /api/hotels"))
                .extracting(GraphEdge::sourceFile, GraphEdge::confidence, GraphEdge::sourceLocation)
                .containsExactlyInAnyOrder(
                        tuple(TEST, Confidence.DECLARED, "L13"),
                        tuple("frontend/src/app/hotel-api.ts", Confidence.INFERRED, "L3"),
                        tuple(".github/workflows/ci.yml", Confidence.INFERRED, "L4"));
        assertThat(uses(g, "route:GET /api/hotels/{}"))
                .extracting(GraphEdge::sourceFile, GraphEdge::confidence, GraphEdge::context)
                .as("a concrete path only fits a template, so even a test's call is inferred")
                .containsExactlyInAnyOrder(
                        tuple(TEST, Confidence.INFERRED, "/api/hotels/nowhere"),
                        tuple("frontend/src/app/hotel-api.ts", Confidence.INFERRED, null),
                        tuple("scripts/smoke.sh", Confidence.INFERRED, "/api/hotels/harborview"));
        assertThat(uses(g, "route:POST /api/reservations"))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation)
                .containsExactlyInAnyOrder(
                        tuple("frontend/src/app/hotel-api.ts", "L9"),
                        tuple("scripts/smoke.sh", "L4"),
                        tuple("README.md", "L4"));
        assertThat(uses(g, "route:GET /hotels/**"))
                .as("the SPA catch-all fits none of the API paths")
                .isEmpty();
        assertThat(g.edges())
                .as("a public URL, a commented line and a path no route has link nowhere")
                .noneMatch(
                        e -> e.sourceLocation().equals("L5") && e.sourceFile().equals("scripts/smoke.sh"))
                .noneMatch(
                        e -> e.sourceLocation().equals("L2") && e.sourceFile().equals("scripts/smoke.sh"))
                .noneMatch(
                        e -> e.sourceLocation().equals("L22") && e.sourceFile().equals(TEST));
    }

    @Test
    void anOpenApiDocumentDefinesRoutesBesideTheHandlers() {
        CodeGraph g = hotel();
        assertThat(g.incident("route:GET /api/hotels/{}"))
                .filteredOn(e -> e.relation().equals(Relations.DEFINES))
                .extracting(GraphEdge::sourceFile, GraphEdge::context)
                .containsExactlyInAnyOrder(
                        tuple(CTRL, "GET /api/hotels/{slug}"), tuple("api/openapi.yaml", "GET /api/hotels/{id}"));
        assertThat(new GraphTools(g).callers("route:DELETE /api/hotels/{id}", 1, 2000))
                .as("a documented route no handler serves is answerable")
                .startsWith("route:DELETE /api/hotels/{}: 1 definition, 0 uses.")
                .contains("defined:\n  api/openapi.yaml:6  as DELETE /api/hotels/{id}", "Nothing uses it.");
        assertThat(SymbolFinder.of(Map.of("kind", "route"), "*/api/hotels*").find(g, Scope.ALL, 2000))
                .isEqualTo("""
                        3 matches
                        route  route:DELETE /api/hotels/{}  1 definition, 0 uses  api/openapi.yaml:6
                        route  route:GET /api/hotels  1 definition, 3 uses  src/main/java/com/acme/HotelController.java
                        route  route:GET /api/hotels/{}  2 definitions, 3 uses  api/openapi.yaml:4""");
    }

    @Test
    void aScriptReadByAnotherReaderStillCarriesItsRequests() {
        String ts = "frontend/src/app/api.ts";
        String text = "export class Api {\n  hotels() {\n    return this.http.get<Hotel[]>('/api/hotels');\n  }\n}\n";
        Fragment oxc = new FragmentBuilder(ts, "code", null).build();
        String ctrl = GraphIds.declared(CTRL, "HotelController");
        Fragment handler = new Fragment(
                List.of(
                        new GraphNode(ctrl, "HotelController", "code", CTRL, "L10"),
                        Anchors.node("route", "GET /api/hotels")),
                List.of(Anchors.edge(
                        ctrl, "route", "GET /api/hotels", Relations.DEFINES, Confidence.DECLARED, CTRL, 0)));
        CodeGraph g = Linker.link(List.of(handler, Extractors.withRequests(oxc, ts, text)));
        assertThat(uses(g, "route:GET /api/hotels"))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation, GraphEdge::confidence)
                .containsExactly(tuple(ts, "L3", Confidence.INFERRED));
    }

    @Test
    void callersOfAKindsStarOrGlobListsItsAnchors() {
        GraphTools tools = new GraphTools(hotel());
        assertThat(tools.callers("route:*", 1, 2000)).startsWith("5 matches\n").contains("route  route:GET /hotels/**");
        assertThat(tools.callers("route:GET /api/hotels*", 1, 2000))
                .isEqualTo(SymbolFinder.of(Map.of("kind", "route"), "GET /api/hotels*")
                        .find(hotel(), Scope.ALL, 2000))
                .startsWith("2 matches");
        assertThat(tools.anchorUses("route:*", 2000)).startsWith("5 matches");
    }

    @Test
    void aRouteAnswersByMethodAndPathAndItsHandlersCallersNameIt() {
        CodeGraph g = hotel();
        String answer = new GraphTools(g).callers("route:get /api/hotels/{slug}", 1, 2000);
        assertThat(answer)
                .startsWith("route:GET /api/hotels/{}: 2 definitions, 3 uses (3 inferred).")
                .contains(
                        "  HotelController.hotel()  src/main/java/com/acme/HotelController.java:19"
                                + "  as GET /api/hotels/{slug}\n",
                        "test:\n  HotelControllerTest.missing()  src/test/java/com/acme/HotelControllerTest.java:21"
                                + "  as /api/hotels/nowhere (inferred)\n",
                        "other:\n  frontend/src/app/hotel-api.ts:6 (inferred)\n"
                                + "  scripts/smoke.sh:3  as /api/hotels/harborview (inferred)");
        assertThat(new GraphTools(g).callers("HotelController.hotels", 1, 2000))
                .contains("It defines route:GET /api/hotels, 3 uses no compiler checks:"
                        + " callers 'route:GET /api/hotels' lists them.");
    }

    /**
     * Requests the front end and the end-to-end tests of jhipster/jhipster-sample-app (at
     * 6b000b5d23a36c45e01472471b84a44fa2464044, Apache-2.0; the files verbatim under
     * src/test/resources/jhipster): Cypress's {@code cy.intercept} with a method and a glob, a custom
     * command's {@code {method, url}} options, and an Angular service whose every path is built from
     * {@code resourceUrl = `${serverApiUrl}api/labels`}, assigned once in the file. Each links to the
     * route it fits; nothing in them did before.
     */
    @Test
    void requestsThroughCypressOptionsObjectsAndAFilesPathConstantLinkToTheirRoutes() throws IOException {
        String cy = "src/test/javascript/cypress/e2e/entity/bank-account.cy.ts";
        String service = "src/main/webapp/app/entities/label/service/label.service.ts";
        String res = "src/main/java/io/github/jhipster/sample/web/rest/";
        List<Fragment> fragments = new ArrayList<>();
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (String[] r : List.of(
                new String[] {"BankAccountResource", "GET /api/bank-accounts"},
                new String[] {"BankAccountResource", "POST /api/bank-accounts"},
                new String[] {"BankAccountResource", "DELETE /api/bank-accounts/{id}"},
                new String[] {"LabelResource", "GET /api/labels"},
                new String[] {"LabelResource", "POST /api/labels"},
                new String[] {"LabelResource", "GET /api/labels/{id}"},
                new String[] {"LabelResource", "PUT /api/labels/{id}"},
                new String[] {"LabelResource", "PATCH /api/labels/{id}"},
                new String[] {"LabelResource", "DELETE /api/labels/{id}"})) {
            String type = GraphIds.declared(res + r[0] + ".java", r[0]);
            nodes.add(new GraphNode(type, r[0], "code", res + r[0] + ".java", "L10"));
            nodes.add(Anchors.node("route", r[1]));
            edges.add(
                    Anchors.edge(type, "route", r[1], Relations.DEFINES, Confidence.DECLARED, res + r[0] + ".java", 0));
        }
        fragments.add(new Fragment(nodes, edges));
        fragments.add(extract(cy, Files.readString(Path.of("src/test/resources/jhipster/bank-account.cy.ts.txt"))));
        fragments.add(extract(service, Files.readString(Path.of("src/test/resources/jhipster/label.service.ts.txt"))));
        CodeGraph g = Linker.link(fragments);

        assertThat(places(g, "route:GET /api/bank-accounts"))
                .as("cy.intercept('GET', '/api/bank-accounts+(?*|)') and an intercept's {method, url}")
                .containsExactly(cy + ":32", cy + ":99");
        assertThat(places(g, "route:POST /api/bank-accounts"))
                .as("cy.intercept('POST', ...) and cy.authenticatedRequest({method: 'POST', url, body})")
                .containsExactly(cy + ":33", cy + ":91");
        assertThat(places(g, "route:DELETE /api/bank-accounts/{}"))
                .as("a glob's * segment and a template's ${...} are the path variable")
                .containsExactly(cy + ":34", cy + ":41");
        assertThat(places(g, "route:POST /api/labels")).contains(service + ":35");
        assertThat(places(g, "route:PUT /api/labels/{}")).containsExactly(service + ":39");
        assertThat(places(g, "route:PATCH /api/labels/{}")).containsExactly(service + ":43");
        assertThat(places(g, "route:GET /api/labels/{}")).containsExactly(service + ":47");
        assertThat(places(g, "route:DELETE /api/labels/{}")).containsExactly(service + ":56");
        assertThat(places(g, "route:GET /api/labels"))
                .as("this.http.get<ILabel[]>(this.resourceUrl, ...) and httpResource's {url: this.resourceUrl}")
                .contains(service + ":52", service + ":20");
    }

    /**
     * jhipster-sample-app's PasswordService (verbatim, same commit): its path follows {@code
     * ${serverApiUrl}}, a base URL another module exports, so it is the path; a template whose head is
     * any other unknown variable ({@code ${owner}}) builds none.
     */
    @Test
    void aPathAfterABaseUrlVariableIsTheRequestsPath() throws IOException {
        String service = "src/main/webapp/app/account/password/password.service.ts";
        String other = "src/main/webapp/app/other.ts";
        String res = "src/main/java/io/github/jhipster/sample/web/rest/AccountResource.java";
        String type = GraphIds.declared(res, "AccountResource");
        Fragment handler = new Fragment(
                List.of(
                        new GraphNode(type, "AccountResource", "code", res, "L10"),
                        Anchors.node("route", "POST /api/account/change-password")),
                List.of(Anchors.edge(
                        type,
                        "route",
                        "POST /api/account/change-password",
                        Relations.DEFINES,
                        Confidence.DECLARED,
                        res,
                        0)));
        CodeGraph g = Linker.link(
                List.of(
                        handler,
                        extract(
                                service,
                                Files.readString(Path.of("src/test/resources/jhipster/password.service.ts.txt"))),
                        extract(
                                other,
                                "export const f = (owner: string) => http.post(`${owner}api/account/change-password`, {});\n")));
        assertThat(places(g, "route:POST /api/account/change-password")).containsExactly(service + ":13");
    }

    /** Each use of {@code route} as {@code file:line}, every site of an edge, in line order. */
    private static List<String> places(CodeGraph g, String route) {
        List<String> out = new ArrayList<>();
        for (GraphEdge e : uses(g, route)) {
            List<String> sites = e.sites().isEmpty() ? List.of(e.sourceLocation()) : e.sites();
            for (String site : sites)
                out.add(e.sourceFile() + ":" + site.substring(1).replaceFirst("-.*$", ""));
        }
        out.sort(Comparator.comparing((String x) -> x.substring(0, x.lastIndexOf(':')))
                .thenComparingInt(x -> Integer.parseInt(x.substring(x.lastIndexOf(':') + 1))));
        return out;
    }

    /** A name assigned twice, or to something that is not a path, is no constant; a path built from one is not read. */
    @Test
    void onlyAPathConstantAssignedOnceInTheFileBuildsARequestsPath() {
        String ts = "frontend/src/app/api.ts";
        String text = """
                let base = '/api/hotels';
                base = '/api/old';
                const name = 'hotels';
                export class Api {
                  hotels() { return this.http.get(base); }
                  byName() { return this.http.get(`${name}/x`); }
                  other() { return this.http.get(this.unknown + '/api/hotels'); }
                }
                """;
        String ctrl = GraphIds.declared(CTRL, "HotelController");
        Fragment handler = new Fragment(
                List.of(
                        new GraphNode(ctrl, "HotelController", "code", CTRL, "L10"),
                        Anchors.node("route", "GET /api/hotels"),
                        Anchors.node("route", "GET /api/old")),
                List.of(
                        Anchors.edge(ctrl, "route", "GET /api/hotels", Relations.DEFINES, Confidence.DECLARED, CTRL, 0),
                        Anchors.edge(ctrl, "route", "GET /api/old", Relations.DEFINES, Confidence.DECLARED, CTRL, 0)));
        CodeGraph g = Linker.link(List.of(handler, extract(ts, text)));
        assertThat(uses(g, "route:GET /api/hotels")).isEmpty();
        assertThat(uses(g, "route:GET /api/old")).isEmpty();
    }
}
