// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.extract.Extractor;
import cc.jumpkick.codegraph.extract.Extractors;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Environment variables: compose, a workflow, a Kubernetes manifest, a Dockerfile, a {@code .env}
 * file and a script set them; a Spring config file, a Dockerfile, a workflow step, a script and code
 * read them; a variable named as a Spring key binds to it; and no value reaches the graph.
 */
class EnvAnchorsTest {

    private static Fragment extract(String rel, String text) {
        Extractor x = Objects.requireNonNull(Extractors.forPath(rel));
        return x.extract(rel, text);
    }

    private static final String SECRET = "s3cr3t-value";

    private static List<Fragment> fragments() {
        List<Fragment> f = new ArrayList<>();
        f.add(extract("compose.yaml", """
                services:
                  app:
                    image: shop:${IMAGE_TAG}
                    environment:
                      DATABASE_URL: jdbc:postgresql://db:5432/shop
                      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/shop
                  worker:
                    environment:
                      - API_TOKEN=%s
                      - LOG_LEVEL
                """.formatted(SECRET)));
        f.add(extract(".env", "IMAGE_TAG=1.2.3\nUNUSED_FLAG=on\n"));
        f.add(extract("Dockerfile", """
                FROM eclipse-temurin:25
                ARG JAR_FILE=target/app.jar
                ENV JAVA_OPTS="-Xmx512m" APP_HOME=/app
                COPY ${JAR_FILE} $APP_HOME/app.jar
                ENTRYPOINT java $JAVA_OPTS -jar $HOME/app.jar
                """));
        f.add(extract(".github/workflows/ci.yml", """
                jobs:
                  test:
                    env:
                      DATABASE_URL: jdbc:h2:mem:test
                    steps:
                      - run: ./gradlew test -Ddb=$DATABASE_URL
                      - run: echo ${{ env.DATABASE_URL }}
                """));
        f.add(extract("k8s/deployment.yaml", """
                spec:
                  containers:
                    - name: app
                      env:
                        - name: DATABASE_URL
                          valueFrom:
                            secretKeyRef: { name: db, key: url }
                """));
        f.add(extract("scripts/run.sh", """
                #!/bin/sh
                export SERVER_PORT=8081
                java -jar app.jar --db="$DATABASE_URL" --home="$HOME"
                """));
        f.add(extract("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: ${DATABASE_URL:jdbc:h2:mem:dev}
                """));
        String app = "src/main/java/com/acme/Flags.java";
        String flags = GraphIds.declared(app, "Flags");
        String read = GraphIds.member(flags, "featureX");
        f.add(new Fragment(
                List.of(
                        new GraphNode(flags, "Flags", "code", app, "L3"),
                        new GraphNode(read, ".featureX()", "code", app, "L5"),
                        Anchors.node("env", "FEATURE_X")),
                List.of(
                        GraphEdge.of(flags, read, Relations.METHOD, Confidence.EXTRACTED, app, 5),
                        Anchors.edge(read, "env", "FEATURE_X", Relations.USES, Confidence.DECLARED, app, 6))));
        return f;
    }

    private static CodeGraph graph() {
        CodeGraph g = Linker.link(fragments());
        g.modules(new Modules(List.of(new Modules.Module("", false))));
        return g;
    }

    private static List<GraphEdge> edges(CodeGraph g, String anchor, String relation) {
        return g.incident(anchor).stream()
                .filter(e -> e.target().equals(anchor) && e.relation().equals(relation))
                .toList();
    }

    @Test
    void everyFileThatSetsAVariableAndEveryReadIsListedWithItsGrade() {
        CodeGraph g = graph();
        assertThat(edges(g, "env:DATABASE_URL", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile, GraphEdge::sourceLocation)
                .containsExactlyInAnyOrder(
                        tuple("compose.yaml", "L5"),
                        tuple(".github/workflows/ci.yml", "L4"),
                        tuple("k8s/deployment.yaml", "L5"));
        assertThat(edges(g, "env:DATABASE_URL", Relations.USES))
                .extracting(GraphEdge::sourceFile, GraphEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("src/main/resources/application.yml", Confidence.DECLARED),
                        tuple(".github/workflows/ci.yml", Confidence.INFERRED),
                        tuple("scripts/run.sh", Confidence.INFERRED));
        assertThat(edges(g, "env:DATABASE_URL", Relations.USES))
                .filteredOn(e -> e.sourceFile().endsWith("ci.yml"))
                .flatExtracting(GraphEdge::sites)
                .as("$VAR in a run line and ${{ env.VAR }}")
                .containsExactly("L6", "L7");
        assertThat(edges(g, "env:API_TOKEN", Relations.DEFINES))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L9");
        assertThat(edges(g, "env:LOG_LEVEL", Relations.DEFINES))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L10");
        assertThat(edges(g, "env:IMAGE_TAG", Relations.USES))
                .extracting(GraphEdge::sourceFile)
                .containsExactly("compose.yaml");
        assertThat(edges(g, "env:JAVA_OPTS", Relations.USES))
                .extracting(GraphEdge::sourceLocation, GraphEdge::confidence)
                .containsExactly(tuple("L5", Confidence.DECLARED));
        assertThat(edges(g, "env:JAR_FILE", Relations.DEFINES))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L2");
        assertThat(edges(g, "env:APP_HOME", Relations.DEFINES))
                .extracting(GraphEdge::sourceLocation)
                .containsExactly("L3");
        assertThat(edges(g, "env:SERVER_PORT", Relations.DEFINES))
                .extracting(GraphEdge::sourceFile)
                .containsExactly("scripts/run.sh");
        assertThat(g.has("env:HOME")).as("a variable every shell has").isFalse();
        assertThat(g.nodes()).as("compose's map keys are variables, not keys").noneMatch(n -> n.id().startsWith(
                        "config:" + "services"));
    }

    @Test
    void aVariableReadAndSetNowhereAndOneSetAndNeverReadAreAnswered() {
        GraphTools tools = new GraphTools(graph());
        assertThat(tools.callers("env:FEATURE_X", 1, 2000))
                .startsWith("env:FEATURE_X: 0 definitions, 1 use (1 declared).")
                .contains("Flags.featureX()  src/main/java/com/acme/Flags.java:6", "No file defines it.");
        assertThat(tools.callers("env:UNUSED_FLAG", 1, 2000))
                .startsWith("env:UNUSED_FLAG: 1 definition, 0 uses.")
                .contains(".env:2", "Nothing uses it.");
    }

    @Test
    void aVariableNamedAsASpringKeyBindsToItAndItsCode() {
        CodeGraph g = graph();
        assertThat(g.incident("env:SPRING_DATASOURCE_URL"))
                .filteredOn(e -> e.relation().equals(Relations.BINDS))
                .extracting(GraphEdge::target, GraphEdge::confidence)
                .containsExactly(tuple("config:spring.datasource.url", Confidence.INFERRED));
        assertThat(new GraphTools(g).callers("env:SPRING_DATASOURCE_URL", 1, 2000))
                .contains(
                        "defined:\n  compose.yaml:6\n",
                        "sets config:spring.datasource.url (inferred): 1 definition, 0 uses:\n"
                                + "    src/main/resources/application.yml:3")
                .doesNotContain("Nothing uses it.");
        assertThat(new GraphTools(g).callers("config:spring.datasource.url", 1, 2000))
                .contains("set by env:SPRING_DATASOURCE_URL (inferred): 1 definition:\n    compose.yaml:6");
    }

    @Test
    void anAnswerNamesTheLineThatSetsAVariableWithoutQuotingIt() {
        String compose = "services:\n  app:\n    environment:\n      API_TOKEN: %s\n".formatted(SECRET);
        CodeGraph g = Linker.link(List.of(extract("compose.yaml", compose)));
        String answer = new GraphTools(g, Scope.ALL, rel -> compose, true).callers("env:API_TOKEN", 1, 2000);
        assertThat(answer).contains("compose.yaml:4").doesNotContain(SECRET);
    }

    @Test
    void noValueOfAnyVariableIsStoredInAFragment() {
        for (Fragment f : fragments()) {
            assertThat(f.toString())
                    .doesNotContain(
                            SECRET,
                            "jdbc:postgresql",
                            "1.2.3",
                            "-Xmx512m",
                            "8081",
                            "target/app.jar",
                            "jdbc:h2:mem:test");
        }
    }
}
