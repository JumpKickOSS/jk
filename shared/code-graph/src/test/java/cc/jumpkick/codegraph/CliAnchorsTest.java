// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * A program's commands and flags, as its compiled classes define them, and the command lines in a
 * script, a workflow, a Dockerfile, a Makefile, a {@code package.json} script and a README's fenced
 * block that pass them: each flag links to its command's anchor, a flag no command defines becomes
 * an anchor nothing defines, and a line calling another tool links nowhere.
 */
class CliAnchorsTest {

    private static final String SECRET = "s3cr3t-token";

    private static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("scripts/smoke.sh", """
                #!/bin/sh
                ./bin/shop serve --listen-port=8080 -vq --token %s &
                git commit -m "shop serve --nothing"
                shop serve --listen-prot 9090
                shop srv --listen-port 9090
                """.formatted(SECRET));
        FILES.put(".github/workflows/ci.yml", """
                jobs:
                  export:
                    steps:
                      - run: shop export --format csv > out.csv
                """);
        FILES.put("Dockerfile", """
                FROM eclipse-temurin:25
                ENTRYPOINT ["shop", "serve", "--listen-port", "8080"]
                """);
        FILES.put("Makefile", """
                run:
                \tshop serve -v -- --format
                """);
        FILES.put("package.json", """
                {
                  "name": "shop-ui",
                  "scripts": {
                    "export": "shop export --format json"
                  }
                }
                """);
        FILES.put("README.md", """
                # Shop

                Run `shop serve --in-prose` to start it.

                ```bash
                $ shop serve --listen-port 8080
                ```
                """);
    }

    /** What the compiled classes give for {@code shop}: its commands and flags, defined by their classes. */
    private static Fragment definitions() {
        String app = "src/main/java/com/acme/ShopCli.java";
        String cli = GraphIds.declared(app, "ShopCli");
        String serve = GraphIds.declared(app, "Serve");
        List<GraphNode> nodes = new ArrayList<>(List.of(
                new GraphNode(cli, "ShopCli", "code", app, "L5"), new GraphNode(serve, "Serve", "code", app, "L9")));
        List<GraphEdge> edges = new ArrayList<>();
        for (String[] d : new String[][] {
            {cli, "shop"},
            {cli, "shop export"},
            {cli, "shop export --format"},
            {serve, "shop serve"},
            {serve, "shop serve --listen-port"},
            {serve, "shop serve -v"},
            {serve, "shop serve -q"},
            {serve, "shop serve --token"}
        }) {
            nodes.add(Anchors.node(Anchors.CLI, d[1]));
            edges.add(Anchors.edge(d[0], Anchors.CLI, d[1], Relations.DEFINES, Confidence.DECLARED, app, 0));
        }
        return new Fragment(nodes, edges);
    }

    private static List<Fragment> fragments() {
        List<Fragment> f = new ArrayList<>();
        f.add(definitions());
        FILES.forEach((rel, text) -> {
            Extractor x = Objects.requireNonNull(Extractors.forPath(rel), rel);
            f.add(x.extract(rel, text));
        });
        return f;
    }

    private static CodeGraph graph() {
        CodeGraph g = Linker.link(fragments());
        g.modules(new Modules(List.of(new Modules.Module("", false))));
        return g;
    }

    private static List<GraphEdge> uses(CodeGraph g, String anchor) {
        return g.incident(anchor).stream()
                .filter(e -> e.target().equals(anchor) && e.relation().equals(Relations.USES))
                .toList();
    }

    @Test
    void eachFileThatPassesAFlagUsesItsCommandsAnchor() {
        CodeGraph g = graph();
        assertThat(uses(g, "cli:shop serve --listen-port"))
                .extracting(GraphEdge::sourceFile, GraphEdge::sites, GraphEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("scripts/smoke.sh", List.of("L2"), Confidence.INFERRED),
                        tuple("Dockerfile", List.of("L2"), Confidence.INFERRED),
                        tuple("README.md", List.of("L6"), Confidence.INFERRED));
        assertThat(uses(g, "cli:shop export --format"))
                .extracting(GraphEdge::sourceFile, GraphEdge::sites)
                .containsExactlyInAnyOrder(
                        tuple(".github/workflows/ci.yml", List.of("L4")), tuple("package.json", List.of("L4")));
        assertThat(uses(g, "cli:shop serve -q"))
                .as("-vq is -v -q, each a flag of serve")
                .extracting(GraphEdge::sourceFile, GraphEdge::context)
                .containsExactly(tuple("scripts/smoke.sh", "-vq"));
        assertThat(uses(g, "cli:shop serve -v"))
                .extracting(GraphEdge::sourceFile)
                .containsExactlyInAnyOrder("scripts/smoke.sh", "Makefile");
        assertThat(uses(g, "cli:shop serve"))
                .extracting(GraphEdge::sourceFile)
                .contains("scripts/smoke.sh", "Dockerfile", "Makefile", "README.md");
        assertThat(g.has("cli:shop serve --format"))
                .as("after --, words are arguments")
                .isFalse();
        assertThat(g.has("cli:shop serve --nothing"))
                .as("a quoted message to another tool")
                .isFalse();
        assertThat(g.has("cli:shop serve --in-prose"))
                .as("prose outside a fenced block")
                .isFalse();
        assertThat(g.has("cli:shop --listen-port"))
                .as("after a word no subcommand is, as an alias may be")
                .isFalse();
    }

    @Test
    void aFlagNoCommandDefinesIsAnAnchorNothingDefines() {
        CodeGraph g = graph();
        assertThat(uses(g, "cli:shop serve --listen-prot"))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation)
                .containsExactly(tuple("scripts/smoke.sh", "L4"));
        assertThat(new GraphTools(g, Scope.ALL, FILES::get, true).callers("cli:shop serve --listen-prot", 1, 2000))
                .startsWith("cli:shop serve --listen-prot: 0 definitions, 1 use (1 inferred).")
                .contains("  scripts/smoke.sh:4 (inferred)", "No file defines it.");
        assertThat(new GraphTools(g, Scope.ALL, FILES::get, true).callers("cli:shop serve --listen-port", 1, 2000))
                .startsWith("cli:shop serve --listen-port: 1 definition, 3 uses (3 inferred).")
                .contains("ShopCli.java", "  Dockerfile:2 (inferred)");
    }

    @Test
    void noValuePassedOnACommandLineIsStoredInAFragment() {
        for (Fragment f : fragments()) {
            assertThat(f.toString()).doesNotContain(SECRET, "8080", "csv");
        }
    }
}
