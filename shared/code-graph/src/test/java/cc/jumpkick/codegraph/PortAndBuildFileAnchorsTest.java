// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The files around the code that no compiler checks: a Spring config, a Dockerfile, compose, a
 * Kubernetes manifest, a workflow, a Makefile and a script name the same ports and variables, and an
 * answer about one lists them all, each at its line.
 */
class PortAndBuildFileAnchorsTest {

    private static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("src/main/resources/application.yml", """
                server:
                  port: ${PORT:8080} # behind the proxy
                shop:
                  api-key: ${SHOP_API_KEY}
                """);
        FILES.put("src/main/resources/application-admin.properties", "server.port=9090\n");
        FILES.put("Dockerfile", """
                FROM eclipse-temurin:25
                EXPOSE 8080/tcp 9090
                HEALTHCHECK CMD curl -f http://localhost:8080/actuator/health || exit 1
                """);
        FILES.put("compose.yaml", """
                services:
                  app:
                    ports:
                      - "8081:8080"
                      - 127.0.0.1:9091:9090
                      - "7000-7005:7000-7005"
                    environment:
                      - SHOP_API_KEY
                  proxy:
                    ports:
                      - target: 8080
                        published: 80
                """);
        FILES.put("k8s/deployment.yaml", """
                spec:
                  containers:
                    - name: app
                      ports:
                        - containerPort: 8080
                """);
        FILES.put(".github/workflows/ci.yml", """
                jobs:
                  smoke:
                    steps:
                      - run: echo "SHOP_REGION=eu" >> "$GITHUB_ENV"
                      - run: |
                          export SHOP_TIER=gold
                          curl -s http://localhost:8080/api/orders
                """);
        FILES.put("Makefile", """
                export SHOP_TIER ?= silver
                SHOP_REGION ?= us
                IMAGE = shop:$(SHOP_TIER)

                # curl http://localhost:1234/ in a comment is nothing
                smoke:
                \tcurl -H "key: $$SHOP_API_KEY" http://localhost:8081/api/orders --region $(SHOP_REGION)
                """);
        FILES.put("scripts/smoke.sh", """
                #!/bin/sh
                if [ -n "$SHOP_REGION" ]; then
                  export SHOP_ZONE=a
                fi
                wget -qO- http://127.0.0.1:9091/admin
                """);
    }

    private static CodeGraph graph() {
        List<Fragment> fragments = FILES.entrySet().stream()
                .map(f -> {
                    Extractor x = Objects.requireNonNull(Extractors.forPath(f.getKey()), f.getKey());
                    return x.extract(f.getKey(), f.getValue());
                })
                .toList();
        CodeGraph g = Linker.link(fragments);
        g.modules(new Modules(List.of(new Modules.Module("", false))));
        return g;
    }

    private static List<GraphEdge> edges(CodeGraph g, String anchor, String relation) {
        return g.incident(anchor).stream()
                .filter(e -> e.target().equals(anchor) && e.relation().equals(relation))
                .toList();
    }

    @Test
    void aPortIsDefinedWhereAServiceListensAndUsedByTheMappingsAndUrlsThatReachIt() {
        CodeGraph g = graph();
        assertThat(edges(g, "port:8080", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation, GraphEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("src/main/resources/application.yml", "L2", Confidence.DECLARED),
                        tuple("Dockerfile", "L2", Confidence.DECLARED),
                        tuple("k8s/deployment.yaml", "L5", Confidence.DECLARED));
        assertThat(edges(g, "port:8080", Relations.USES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sites, GraphEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("compose.yaml", List.of("L4"), Confidence.DECLARED),
                        tuple("Dockerfile", List.of("L3"), Confidence.INFERRED),
                        tuple(".github/workflows/ci.yml", List.of("L7"), Confidence.INFERRED));
        assertThat(edges(g, "port:8081", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation)
                .containsExactly(tuple("compose.yaml", "L4"));
        assertThat(edges(g, "port:8081", Relations.USES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation, GraphEdge::confidence)
                .containsExactly(tuple("Makefile", "L7", Confidence.INFERRED));
        assertThat(edges(g, "port:9090", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile)
                .containsExactlyInAnyOrder("src/main/resources/application-admin.properties", "Dockerfile");
        assertThat(edges(g, "port:9091", Relations.USES))
                .extracting(GraphEdge::sourceFile, GraphEdge::confidence)
                .containsExactly(tuple("scripts/smoke.sh", Confidence.INFERRED));
        assertThat(edges(g, "port:80", Relations.DEFINES))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L12");
        assertThat(g.has("port:7000")).as("a range is skipped").isFalse();
        assertThat(g.has("port:1234"))
                .as("a URL in a comment, to a port nothing else names")
                .isFalse();
    }

    @Test
    void aMakefileAndAWorkflowStepSetAndReadVariablesBesideTheScriptsAndCompose() {
        CodeGraph g = graph();
        assertThat(edges(g, "env:SHOP_TIER", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation)
                .containsExactlyInAnyOrder(tuple("Makefile", "L1"), tuple(".github/workflows/ci.yml", "L6"));
        assertThat(edges(g, "env:SHOP_TIER", Relations.USES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation, GraphEdge::confidence)
                .containsExactly(tuple("Makefile", "L3", Confidence.INFERRED));
        assertThat(edges(g, "env:SHOP_REGION", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation)
                .containsExactly(tuple(".github/workflows/ci.yml", "L4"));
        assertThat(edges(g, "env:SHOP_REGION", Relations.USES))
                .as("?= reads the variable, declared; $(VAR) in a recipe may, inferred: one edge, the surer grade")
                .extracting(GraphEdge::sourceFile, GraphEdge::sites, GraphEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("Makefile", List.of("L2", "L7"), Confidence.DECLARED),
                        tuple("scripts/smoke.sh", List.of("L2"), Confidence.INFERRED));
        assertThat(edges(g, "env:SHOP_API_KEY", Relations.USES))
                .as("$$VAR in a recipe is the shell's read")
                .extracting(GraphEdge::sourceFile)
                .containsExactlyInAnyOrder("src/main/resources/application.yml", "Makefile");
        assertThat(edges(g, "env:SHOP_ZONE", Relations.DEFINES))
                .as("an indented export")
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L3");
        assertThat(g.has("env:IMAGE"))
                .as("a Make variable no environment names")
                .isFalse();
    }

    @Test
    void aPortAnswerListsTheFilesAroundTheCodeWithTheirLines() {
        CodeGraph g = graph();
        String answer = new GraphTools(g, Scope.ALL, FILES::get, true).callers("port:8080", 1, 2000);
        assertThat(answer)
                .startsWith("port:8080: 3 definitions, 3 uses (1 declared, 2 inferred).")
                .contains(
                        "defined:\n  Dockerfile:2\n",
                        "  compose.yaml:4\n",
                        "  .github/workflows/ci.yml:7 (inferred)\n",
                        "curl -s http://localhost:8080/api/orders");
        assertThat(new GraphTools(g, Scope.ALL, FILES::get, true).callers("env:SHOP_REGION", 1, 2000))
                .startsWith("env:SHOP_REGION: 1 definition, 2 uses (1 declared, 1 inferred).")
                .contains("  Makefile:2,7\n", "  scripts/smoke.sh:2 (inferred)\n");
    }
}
