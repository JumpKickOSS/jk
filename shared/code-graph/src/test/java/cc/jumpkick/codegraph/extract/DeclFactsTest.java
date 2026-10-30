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
    void anAnnotationBetweenTheModifiersAndTheTypeStillDeclaresTheMember() {
        Fragment f = Objects.requireNonNull(Extractors.forPath("src/main/java/a/Lookup.java"))
                .extract("src/main/java/a/Lookup.java", """
                        package a;

                        public class Lookup {
                            private static final @Nullable String FALLBACK = null;

                            public @Inject Lookup(String root) {
                            }

                            static @Nullable String find(String key) {
                                return null;
                            }

                            public static <T> @org.jspecify.annotations.Nullable T first(java.util.List<T> xs) {
                                return null;
                            }

                            private @Size(max = 3) java.util.List<String> names() {
                                return null;
                            }
                        }
                        """);
        assertThat(Decl.kind(node(f, ".find()"))).isEqualTo("method");
        assertThat(Decl.vis(node(f, ".find()"))).isEqualTo("package");
        assertThat(Decl.vis(node(f, ".first()"))).isEqualTo("public");
        assertThat(Decl.vis(node(f, ".names()"))).isEqualTo("private");
        assertThat(Decl.kind(node(f, ".Lookup()"))).isEqualTo("constructor");
        assertThat(Decl.kind(node(f, ".FALLBACK"))).isEqualTo("constant");
    }

    @Test
    void namedConstantsAreMembersOfTheirType() {
        Fragment f = Objects.requireNonNull(Extractors.forPath("src/main/java/a/Pricing.java"))
                .extract("src/main/java/a/Pricing.java", """
                        package a;

                        public final class Pricing {
                            /** Seven nights or more. */
                            public static final int LONG_STAY_NIGHTS = 7;
                            private static final java.util.List<String> NAMES =
                                    java.util.List.of("a");
                            private static final org.slf4j.Logger log = null;
                            private final int limit = 3;

                            void price() {
                                final int LOCAL = 1;
                            }
                        }
                        """);
        GraphNode nights = node(f, ".LONG_STAY_NIGHTS");
        assertThat(Decl.kind(nights)).isEqualTo("constant");
        assertThat(Decl.vis(nights)).isEqualTo("public");
        GraphNode names = node(f, ".NAMES");
        assertThat(Decl.vis(names)).isEqualTo("private");
        assertThat(Decl.end(names)).isEqualTo(7);
        assertThat(f.nodes()).extracting(GraphNode::label).doesNotContain(".log", ".limit", ".LOCAL");

        Fragment kt = Objects.requireNonNull(Extractors.forPath("src/main/kotlin/a/Limits.kt"))
                .extract("src/main/kotlin/a/Limits.kt", """
                        package a

                        const val MAX_GUESTS = 4

                        class Limits {
                            companion object {
                                const val MAX_NIGHTS = 30
                            }
                        }
                        """);
        assertThat(Decl.kind(node(kt, "MAX_GUESTS"))).isEqualTo("constant");
        assertThat(Decl.kind(node(kt, ".MAX_NIGHTS"))).isEqualTo("constant");
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
