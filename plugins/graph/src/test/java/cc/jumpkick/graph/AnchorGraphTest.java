// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.GraphTools;
import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.codegraph.Relations;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiled classes reach configuration anchors: a {@code ${key}} placeholder in an annotation is a
 * declared use from the member it annotates, whether or not a file sets the key; a key-shaped
 * literal is an inferred use, made only when a file names the key; a key two profile files set is
 * one anchor. No anchor id the compiled classes contribute names the JVM.
 */
class AnchorGraphTest {

    private static final Map<String, String> FILES = Map.of(
            "src/main/java/org/springframework/beans/factory/annotation/Value.java", """
                    package org.springframework.beans.factory.annotation;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Value {
                        String value();
                    }
                    """,
            "src/main/java/com/acme/shop/Shop.java", """
                    package com.acme.shop;

                    import org.springframework.beans.factory.annotation.Value;

                    public class Shop {
                        @Value("${shop.maxItems:10}")
                        int maxItems;

                        @Value("${shop.region}")
                        String region;

                        String currency(java.util.Properties p) {
                            return p.getProperty("shop.currency");
                        }

                        String os(java.util.Properties p) {
                            return p.getProperty("os.name");
                        }

                        @Value("${API_KEY}")
                        String apiKey;

                        String database() {
                            return System.getenv("DATABASE_URL");
                        }
                    }
                    """,
            "src/main/resources/application.yml", """
                    shop:
                      max-items: 20
                      currency: EUR
                    """,
            "src/main/resources/application-prod.properties", "shop.max-items=50\n");

    private static final Clock LATER = new Clock() {
        @Override
        public long millis() {
            return Long.MAX_VALUE / 2;
        }

        @Override
        public long nanos() {
            return 0;
        }
    };

    private static CodeGraph graph(Path dir) throws IOException {
        return graph(dir, FILES);
    }

    /** The graph of a one-module workspace holding {@code files}, its Java compiled as a build would. */
    static CodeGraph graph(Path dir, Map<String, String> files) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "shop"
                version = "1.0.0"
                java = 25
                """);
        for (Map.Entry<String, String> f : files.entrySet()) {
            Path p = dir.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue());
        }
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        List<String> args = new ArrayList<>(List.of("-g", "-d", classes.toString()));
        try (var walk = Files.walk(dir.resolve("src/main/java"))) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, args.toArray(String[]::new))).isZero();
        FactsIndexing.Ensured e = FactsIndexing.ensure(classes, FactsIndexing.indexPath(dir.resolve("target"), "main"));
        List<GraphBuild.ClassIndex> indexes = List.of(
                new GraphBuild.ClassIndex(e.index(), "", "main", e.bodyDigest(), e.classes(), Set.of(), List.of()));
        return GraphBuild.refresh(
                        dir,
                        dir.resolve("target/graph"),
                        dir.resolve("target/jk-cache"),
                        indexes,
                        Modules.NONE,
                        new ScriptReader(null, null),
                        false,
                        GraphBuild.Progress.NONE,
                        LATER)
                .graph();
    }

    @Test
    void compiledAnnotationsAndLiteralsUseConfigAnchorsWithTheirGrades(@TempDir Path dir) throws IOException {
        CodeGraph g = graph(dir);
        assertThat(g.incident("config:shop.maxitems"))
                .extracting(GraphEdge::sourceFile, GraphEdge::relation, GraphEdge::confidence)
                .containsExactlyInAnyOrder(
                        tuple("src/main/resources/application.yml", Relations.DEFINES, Confidence.DECLARED),
                        tuple("src/main/resources/application-prod.properties", Relations.DEFINES, Confidence.DECLARED),
                        tuple("src/main/java/com/acme/shop/Shop.java", Relations.USES, Confidence.DECLARED));
        assertThat(g.incident("config:shop.region"))
                .as("read in code and set in no file: the anchor is there to say so")
                .extracting(GraphEdge::relation, GraphEdge::confidence)
                .containsExactly(tuple(Relations.USES, Confidence.DECLARED));
        assertThat(g.incident("config:shop.currency"))
                .filteredOn(e -> e.relation().equals(Relations.USES))
                .extracting(GraphEdge::source, GraphEdge::confidence)
                .containsExactly(tuple("src_main_java_com_acme_shop_shop_shop_currency", Confidence.INFERRED));
        assertThat(g.has("config:os.name")).isFalse();
        assertThat(g.incident("env:DATABASE_URL"))
                .as("System.getenv with a literal is a declared read, at its line")
                .extracting(e -> g.display(e.source()), GraphEdge::confidence, GraphEdge::sourceLocation)
                .containsExactly(tuple("Shop.database()", Confidence.DECLARED, "L24"));
        assertThat(g.incident("env:API_KEY"))
                .as("a placeholder named as a variable reads the variable")
                .extracting(GraphEdge::relation, GraphEdge::confidence)
                .containsExactly(tuple(Relations.USES, Confidence.DECLARED));
        assertThat(g.has("config:apikey")).isFalse();

        String region = new GraphTools(g).callers("config:shop.region", 1, 2000);
        assertThat(region)
                .startsWith("config:shop.region: 0 definitions, 1 use (1 declared).")
                .contains("Shop  src/main/java/com/acme/shop/Shop.java:", "No file defines it.");
        assertThat(new GraphTools(g).callers("shop.currency", 1, 2000))
                .as("a bare label a symbol also answers to is the symbol's: Shop.currency()")
                .startsWith("Callers of Shop.currency()");
        assertThat(new GraphTools(g).callers("config:shop.currency", 1, 2000))
                .startsWith("config:shop.currency: 1 definition, 1 use (1 inferred).")
                .contains("Shop.currency()  src/main/java/com/acme/shop/Shop.java:13 (inferred)");
    }

    @Test
    void noAnchorFromCompiledClassesNamesTheJvm(@TempDir Path dir) throws IOException {
        CodeGraph g = graph(dir);
        List<String> ids =
                g.nodes().stream().filter(Anchors::is).map(GraphNode::id).toList();
        assertThat(ids).contains("config:shop.maxitems", "config:shop.region", "config:shop.currency");
        for (String id : ids) {
            assertThat(id)
                    .matches("[a-z][a-z0-9.-]*:.+")
                    .doesNotContainPattern("\\(L?[\\w/$;\\[]*\\)[VZBCSIJFDL\\[]")
                    .doesNotContainPattern("L[a-z]\\w*/[\\w/$]+;")
                    .doesNotContainPattern("\\.class\\b")
                    .doesNotContainPattern("com[./]acme")
                    .doesNotContainPattern("\\b[\\w.-]+:[\\w.-]+:\\d[\\w.-]*");
        }
        assertThat(g.edges().stream()
                        .filter(e -> Anchors.is(g.node(e.target())))
                        .map(e -> e.confidence().grade())
                        .distinct())
                .isSubsetOf("bound", "declared", "inferred");
    }
}
