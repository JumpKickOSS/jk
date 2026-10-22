// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Decl;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.codegraph.GraphNode;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class DeclFactsTest {

    private static GraphNode node(Fragment f, String label) {
        return f.nodes().stream()
                .filter(n -> n.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node " + label));
    }

    @Test
    void javaTypesAndMembersGetKindVisibilitySpanAndAnnotations() {
        Fragment f = Objects.requireNonNull(Extractors.forPath("src/main/java/a/Shop.java"))
                .extract("src/main/java/a/Shop.java", """
                        package a;

                        @Service
                        @Deprecated(since = "1")
                        public class Shop {
                            private final int x = 1;

                            Shop() {
                            }

                            @Transactional(readOnly = true)
                            public long total(java.util.List<Long> cents) throws Exception {
                                if (x > 0) {
                                    return 1;
                                }
                                return 0;
                            }

                            interface Port {
                                void send(String s);
                            }

                            private enum Mode { A, B }

                            record Point(int x, int y) {}
                        }
                        """);
        GraphNode shop = node(f, "Shop");
        assertThat(Decl.kind(shop)).isEqualTo("class");
        assertThat(Decl.vis(shop)).isEqualTo("public");
        assertThat(Decl.end(shop)).isEqualTo(26);
        assertThat(Decl.annotations(shop)).containsExactly("Deprecated", "Service");
        GraphNode total = node(f, ".total()");
        assertThat(Decl.kind(total)).isEqualTo("method");
        assertThat(Decl.vis(total)).isEqualTo("public");
        assertThat(Decl.line(total)).isEqualTo(12);
        assertThat(Decl.end(total)).isEqualTo(17);
        assertThat(Decl.annotations(total)).containsExactly("Transactional");
        GraphNode ctor = node(f, ".Shop()");
        assertThat(Decl.kind(ctor)).isEqualTo("constructor");
        assertThat(Decl.vis(ctor)).isEqualTo("package");
        assertThat(Decl.end(ctor)).isEqualTo(9);
        assertThat(Decl.kind(node(f, "Port"))).isEqualTo("interface");
        GraphNode send = node(f, ".send()");
        assertThat(Decl.vis(send))
                .as("an interface member is implicitly public")
                .isEqualTo("public");
        assertThat(Decl.end(send)).isEqualTo(20);
        assertThat(Decl.kind(node(f, "Mode"))).isEqualTo("enum");
        assertThat(Decl.vis(node(f, "Mode"))).isEqualTo("private");
        assertThat(Decl.kind(node(f, "Point"))).isEqualTo("record");
    }

    @Test
    void kotlinDeclarationsDefaultToPublicAndReadExpressionBodies() {
        Fragment f = Objects.requireNonNull(Extractors.forPath("src/main/kotlin/a/Cart.kt"))
                .extract("src/main/kotlin/a/Cart.kt", """
                        package a

                        internal object Registry {
                            fun size(): Int = 3
                        }

                        enum class Color { RED }

                        class Cart(val items: List<Int>) {
                            private fun total(): Int =
                                items.sum()
                                    .plus(1)

                            fun empty(): Boolean {
                                return items.isEmpty()
                            }
                        }

                        fun formatCents(cents: Long): String = "$cents"
                        """);
        assertThat(Decl.kind(node(f, "Registry"))).isEqualTo("object");
        assertThat(Decl.vis(node(f, "Registry"))).isEqualTo("internal");
        assertThat(Decl.kind(node(f, "Color"))).isEqualTo("enum");
        assertThat(Decl.vis(node(f, "Cart"))).isEqualTo("public");
        GraphNode total = node(f, ".total()");
        assertThat(Decl.vis(total)).isEqualTo("private");
        assertThat(Decl.end(total)).isEqualTo(12);
        assertThat(Decl.end(node(f, ".empty()"))).isEqualTo(16);
        GraphNode fmt = node(f, "formatCents()");
        assertThat(Decl.kind(fmt)).isEqualTo("function");
        assertThat(Decl.end(fmt)).isEqualTo(19);
        assertThat(Decl.end(node(f, ".size()"))).isEqualTo(4);
    }

    @Test
    void groovyMethodsAreRead() {
        Fragment f = Objects.requireNonNull(Extractors.forPath("src/main/groovy/a/Tool.groovy"))
                .extract("src/main/groovy/a/Tool.groovy", """
                        package a

                        class Tool {
                            def run(String x) {
                                println x
                            }
                        }
                        """);
        assertThat(Decl.kind(node(f, "Tool"))).isEqualTo("class");
        assertThat(Decl.end(node(f, ".run()"))).isEqualTo(6);
    }

    @Test
    void mergeKeepsTheSpecificKindTheWiderVisibilityAndEveryAnnotation() {
        var source = DeclScan.facts("object", "package", 9, List.of("Service"));
        var bytecode = DeclScan.facts("class", "public", 0, List.of("Component"));
        var merged = Decl.merge(source, bytecode);
        assertThat(merged)
                .containsEntry(Decl.KIND, "object")
                .containsEntry(Decl.VIS, "public")
                .containsEntry(Decl.END, 9)
                .containsEntry(Decl.ANN, List.of("Component", "Service"));
        assertThat(Decl.merge(DeclScan.facts("class", "internal", 3, List.of()), bytecode))
                .containsEntry(Decl.VIS, "internal");
    }
}
