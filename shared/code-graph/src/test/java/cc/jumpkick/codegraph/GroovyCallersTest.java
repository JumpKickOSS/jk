// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.extract.Extractors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Groovy that is not {@code @CompileStatic} binds its calls at run time, by name, so its classes
 * hold none of them: {@code callers} lists such a call as inferred and never says complete while
 * dynamic Groovy can reach the method; {@code @CompileStatic} calls are exact.
 */
class GroovyCallersTest {

    private static final String STORE = "src/main/java/demo/Store.java";
    private static final String DYN = "src/main/groovy/demo/Dyn.groovy";
    private static final String TYPED = "src/main/groovy/demo/Typed.groovy";

    private static final String STORE_TEXT = """
            package demo;

            public class Store {
                public int getSize() {
                    return 0;
                }

                public void put(String item) {}
            }
            """;

    private static final String DYN_TEXT = """
            package demo

            class Dyn {
                void fill(Store store) {
                    store.put("a")
                    println store.size
                }

                void loose(def anything) {
                    anything.put "b"
                }
            }
            """;

    private static final String TYPED_TEXT = """
            package demo

            import groovy.transform.CompileStatic

            @CompileStatic
            class Typed {
                void fill(Store store) {
                    store.put("c")
                }
            }
            """;

    /** The sources as the scan reads them, and the one call the classes hold: the static one. */
    private static CodeGraph graph(Map<String, String> files) {
        List<Fragment> fragments = new ArrayList<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            fragments.add(Objects.requireNonNull(Extractors.forPath(f.getKey())).extract(f.getKey(), f.getValue()));
        }
        String put = GraphIds.member(GraphIds.declared(STORE, "Store"), "put");
        List<GraphNode> methods = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        // The compiled classes' methods, each at its first line, as the facts mapper makes them.
        for (String[] m :
                List.of(new String[] {DYN, "Dyn", "fill", "5"}, new String[] {DYN, "Dyn", "loose", "10"}, new String[] {
                    TYPED, "Typed", "fill", "8"
                })) {
            if (!files.containsKey(m[0])) continue;
            String type = GraphIds.declared(m[0], m[1]);
            String id = GraphIds.member(type, m[2]);
            methods.add(new GraphNode(id, "." + m[2] + "()", "code", m[0], "L" + m[3]));
            edges.add(GraphEdge.of(type, id, Relations.METHOD, Confidence.EXTRACTED, m[0], Integer.parseInt(m[3])));
        }
        String typedFill = GraphIds.member(GraphIds.declared(TYPED, "Typed"), "fill");
        edges.add(GraphEdge.of(typedFill, put, Relations.CALLS, Confidence.EXTRACTED, TYPED, 8));
        fragments.add(new Fragment(methods, edges));
        return CodeGraph.merge(fragments);
    }

    private static final Map<String, String> ALL = Map.of(STORE, STORE_TEXT, DYN, DYN_TEXT, TYPED, TYPED_TEXT);

    @Test
    void a_static_caller_is_exact_and_a_dynamic_one_is_inferred_and_never_complete() {
        String answer = new GraphTools(graph(ALL), Scope.ALL, ALL::get, true).callers("Store.put", 1, 2000);
        assertThat(answer)
                .startsWith("Usages of Store.put(): 1 in main sources, 0 in tests.")
                .contains("Not complete: dynamic Groovy (1 source that can see it, not @CompileStatic)")
                .contains("  Typed.fill()  src/main/groovy/demo/Typed.groovy:8\n")
                .contains("inferred:\n  Dyn.fill()  src/main/groovy/demo/Dyn.groovy:5 (inferred)")
                .contains("  Dyn.loose()  src/main/groovy/demo/Dyn.groovy:10 (inferred)")
                .doesNotContain("Complete from");
    }

    @Test
    void a_property_read_is_an_inferred_call_of_its_getter() {
        assertThat(new GraphTools(graph(ALL), Scope.ALL, ALL::get, true).callers("Store.getSize", 1, 2000))
                .contains("Dyn.fill()  src/main/groovy/demo/Dyn.groovy:6 (inferred)", "L6| println store.size");
    }

    @Test
    void only_static_groovy_or_a_groovy_source_that_cannot_see_the_method_leaves_the_answer_complete() {
        Map<String, String> typedOnly = Map.of(STORE, STORE_TEXT, TYPED, TYPED_TEXT);
        assertThat(new GraphTools(graph(typedOnly), Scope.ALL, typedOnly::get, true).callers("Store.put", 1, 2000))
                .contains("Complete from the compiled classes")
                .doesNotContain("inferred");
        assertThat(new GraphTools(graph(ALL), Scope.ALL, ALL::get, true, (from, to) -> !from.equals(DYN))
                        .callers("Store.put", 1, 2000))
                .contains("Complete from the compiled classes")
                .doesNotContain("Dyn.groovy");
    }

    @Test
    void a_dynamic_groovy_methods_own_declaration_is_not_a_call() {
        String shelf = "src/main/groovy/demo/Shelf.groovy";
        Map<String, String> files = Map.of(shelf, """
                package demo

                class Shelf {
                    String label() {
                        "shelf"
                    }
                }
                """);
        List<Fragment> fragments = new ArrayList<>();
        fragments.add(Objects.requireNonNull(Extractors.forPath(shelf))
                .extract(shelf, Objects.requireNonNull(files.get(shelf))));
        String type = GraphIds.declared(shelf, "Shelf");
        String label = GraphIds.member(type, "label");
        fragments.add(new Fragment(
                List.of(new GraphNode(label, ".label()", "code", shelf, "L5")),
                List.of(GraphEdge.of(type, label, Relations.METHOD, Confidence.EXTRACTED, shelf, 5))));
        assertThat(new GraphTools(CodeGraph.merge(fragments), Scope.ALL, files::get, true)
                        .callers("Shelf.label", 1, 2000))
                .contains("Not complete: dynamic Groovy")
                .doesNotContain("inferred:")
                .doesNotContain("Shelf.groovy:4");
    }
}
