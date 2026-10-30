// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.codegraph.CodeGraph;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphEdge;
import cc.jumpkick.codegraph.GraphNode;
import cc.jumpkick.codegraph.Linker;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.ShopFixture;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ExtractorsTest {

    static CodeGraph shop() throws IOException {
        return ShopFixture.graph();
    }

    private static boolean edge(CodeGraph g, String from, String to, String relation) {
        return g.edges().stream()
                .anyMatch(e -> e.source().equals(from)
                        && e.target().equals(to)
                        && e.relation().equals(relation));
    }

    /** javac copies a constant into its user and leaves no trace; the source says where it came from. */
    @Test
    void aConstantReadThroughItsTypeReferencesIt() {
        String greet = "src/main/java/com/example/lib/Greet.java";
        String app = "src/main/java/com/example/app/App.java";
        CodeGraph g = Linker.link(
                List.of(
                        Objects.requireNonNull(Extractors.forPath(greet))
                                .extract(
                                        greet,
                                        "package com.example.lib;\n\npublic final class Greet {\n"
                                                + "    public static final String PREFIX = \"hi \";\n}\n"),
                        Objects.requireNonNull(Extractors.forPath(app))
                                .extract(
                                        app,
                                        "package com.example.app;\n\nimport com.example.lib.Greet;\n\n"
                                                + "public final class App {\n    static int n() {\n        return Greet.PREFIX.length();\n    }\n}\n")));
        assertThat(edge(
                        g,
                        "src_main_java_com_example_app_app_app",
                        "src_main_java_com_example_lib_greet_greet_prefix",
                        "references"))
                .as("edges: " + g.edges())
                .isTrue();
    }

    @Test
    void sourceScanGivesIdsAndLines() throws IOException {
        CodeGraph g = shop();
        String svc = "src_main_java_com_acme_shop_orderservice_orderservice";
        assertThat(g.node(svc))
                .extracting(GraphNode::label, GraphNode::sourceLocation)
                .containsExactly("OrderService", "L6");
        assertThat(g.node(svc + "_total")).extracting(GraphNode::label).isEqualTo(".total()");
        assertThat(g.node("src_main_kotlin_com_acme_shop_pricecalculator_formatcents"))
                .extracting(GraphNode::label, GraphNode::sourceLocation)
                .containsExactly("formatCents()", "L7");
        assertThat(edge(g, svc, "src_main_java_com_acme_shop_baseservice_baseservice", "inherits"))
                .isTrue();
        assertThat(edge(g, svc, "src_main_java_com_acme_shop_auditable_auditable", "implements"))
                .isTrue();
        assertThat(edge(g, "src_main_java_com_acme_shop_orderservice", svc, "contains"))
                .isTrue();
        assertThat(g.nodes())
                .anyMatch(n -> n.fileType().equals("rationale") && n.label().startsWith("NOTE: totals"));
    }

    @Test
    void markdownLinksAndCodeSpansLinkToFilesAndCode() throws IOException {
        CodeGraph g = shop();
        assertThat(g.node("readme_design")).isNotNull();
        assertThat(edge(g, "readme_shop", "readme_design", "contains")).isTrue();
        assertThat(edge(g, "readme_design", "docs_notes", "references")).isTrue();
        assertThat(g.edges())
                .filteredOn(
                        e -> e.source().equals("readme_shop") && e.relation().equals("references"))
                .extracting(GraphEdge::target)
                .contains(
                        "src_main_java_com_acme_shop_orderservice_orderservice",
                        "src_main_kotlin_com_acme_shop_pricecalculator_pricecalculator");
    }

    @Test
    void springConfigKeysAreNodesAndPlaceholdersLinkToThem() {
        Fragment yml = ConfigExtractors.yaml("src/main/resources/application.yml", """
                server:
                  port: 8080
                shop:
                  currency: EUR
                  pricing:
                    calculator: com.acme.shop.PriceCalculator
                ---
                spring:
                  application:
                    name: shop
                """);
        assertThat(yml.nodes())
                .extracting(GraphNode::label)
                .contains("server.port", "shop.currency", "shop.pricing.calculator", "spring.application.name");
        assertThat(yml.nodes())
                .filteredOn(n -> n.label().equals("shop.currency"))
                .extracting(GraphNode::sourceLocation)
                .containsExactly("L4");
        assertThat(yml.mentions())
                .contains(new Mention(
                        "src_main_resources_application_yml", Mention.Kind.CLASS, "com.acme.shop.PriceCalculator", 6));
        Fragment props =
                ConfigExtractors.properties("src/main/resources/messages.properties", "greeting=Hi ${user.name}\n");
        assertThat(props.nodes()).hasSize(1);
        assertThat(props.mentions())
                .extracting(Mention::kind, Mention::text)
                .containsExactly(tuple(Mention.Kind.CONFIG_KEY, "user.name"));
    }

    @Test
    void buildFilesDeclareSharedDependencyNodes() {
        Fragment pom = MarkupExtractors.xml("pom.xml", """
                <project>
                  <modules>
                    <module>core</module>
                  </modules>
                  <dependencies>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Fragment jk = ConfigExtractors.toml("app/jk.toml", """
                [dependencies]
                guava = { group = "com.google.guava", version = "33.5.0-jre" }
                "org.slf4j:slf4j-api" = "2.0.17"
                """);
        assertThat(pom.nodes()).extracting(GraphNode::id).contains("maven_com_google_guava_guava");
        assertThat(jk.nodes())
                .extracting(GraphNode::id)
                .contains("maven_com_google_guava_guava", "maven_org_slf4j_slf4j_api");
        assertThat(pom.mentions()).extracting(Mention::text).contains("core/pom.xml");
    }

    @Test
    void scriptScanFindsImportsDeclarationsAndAngularFiles() {
        Fragment ts = ScriptScanner.extract("web/src/app/cart/cart.component.ts", """
                import { Component } from '@angular/core';
                import { CartService } from '../services/cart.service';

                // NOTE: the cart re-prices on every change
                @Component({
                  selector: 'app-cart',
                  templateUrl: './cart.component.html',
                  styleUrls: ['./cart.component.scss'],
                })
                export class CartComponent {
                  constructor(private cart: CartService) {}

                  total(): number {
                    return this.cart.total();
                  }
                }

                export const formatTotal = (n: number) => n.toFixed(2);
                """);
        assertThat(ts.nodes()).extracting(GraphNode::label).contains("CartComponent", ".total()", "formatTotal()");
        assertThat(ts.nodes()).extracting(GraphNode::id).contains("npm_angular_core");
        assertThat(ts.mentions())
                .extracting(Mention::kind, Mention::text)
                .contains(
                        tuple(Mention.Kind.MODULE, "web/src/app/services/cart.service"),
                        tuple(Mention.Kind.FILE, "./cart.component.html"));
        assertThat(ts.nodes()).anyMatch(n -> n.fileType().equals("rationale"));
    }

    @Test
    void serviceFilesNameTheirInterfaceAndImplementations() {
        Fragment f = ConfigExtractors.classList(
                "src/main/resources/META-INF/services/com.acme.spi.Plugin", "# impls\ncom.acme.impl.FastPlugin\n");
        assertThat(f.mentions())
                .extracting(Mention::text)
                .containsExactly("com.acme.spi.Plugin", "com.acme.impl.FastPlugin");
        assertThat(Extractors.forPath("src/main/resources/META-INF/services/com.acme.spi.Plugin"))
                .isNotNull();
        assertThat(Extractors.forPath("logo.png")).isNull();
    }

    /** Ids for the same file: nested types at file level, constructors as members. */
    @Test
    void nestedTypesAndConstructorsTakeFileLevelIds() {
        Fragment f = JvmSourceScanner.extract("src/a/Outer.java", """
                package a;

                public class Outer {
                    public Outer(int x) {}

                    static class Inner {
                        void go() {}
                    }

                    void run() { new Inner().go(); }
                }
                """);
        assertThat(f.nodes())
                .extracting(GraphNode::id)
                .contains(
                        "src_a_outer_outer",
                        "src_a_outer_outer_outer",
                        "src_a_outer_inner",
                        "src_a_outer_inner_go",
                        "src_a_outer_outer_run");
        Fragment kt = JvmSourceScanner.extract("src/a/Fns.kt", """
                package a

                class Box(val v: Int) {
                    class Lid
                    fun open() = v
                }
                """);
        assertThat(kt.nodes())
                .extracting(GraphNode::id)
                .contains("src_a_fns_box", "src_a_fns_lid", "src_a_fns_box_open");
    }
}
