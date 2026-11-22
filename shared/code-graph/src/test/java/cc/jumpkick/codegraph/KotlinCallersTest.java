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
 * What {@code callers} answers for the Kotlin shapes the compiled classes hide: a top-level function
 * named by its file, a function the classes know by its {@code @JvmName}, and an {@code inline}
 * function, whose body kotlinc copies into each caller.
 */
class KotlinCallersTest {

    private static final String API = "src/main/kotlin/demo/Api.kt";
    private static final String USE = "src/main/kotlin/demo/Use.kt";

    private static final String API_TEXT = """
            package demo

            inline fun twice(block: () -> Int): Int = block() + block()

            fun greet(name: String, punct: String = "!"): String = "Hello $name$punct"

            @JvmName("sumOf")
            fun total(xs: List<Int>): Int = xs.sum()

            class Account(var balance: Int) {
                inline fun audit(check: (Int) -> Boolean): Boolean = check(balance)
            }
            """;

    private static final String USE_TEXT = """
            package demo

            class Use {
                fun a() = twice { 1 }

                fun c() = greet("x")

                fun e() = total(listOf(1, 2))

                fun g(acc: Account) {
                    println(acc.audit { it > 0 })
                }
            }
            """;

    private static final Map<String, String> FILES = Map.of(API, API_TEXT, USE, USE_TEXT);

    /**
     * Both files as the source scan reads them, plus what the compiled classes add: the call to
     * {@code greet}, and the call to {@code sumOf}, the compiled name of {@code total}. There is no
     * call to {@code twice} or {@code audit}: kotlinc inlined them.
     */
    private static GraphTools tools() {
        List<Fragment> fragments = new ArrayList<>();
        for (Map.Entry<String, String> f : FILES.entrySet()) {
            fragments.add(Objects.requireNonNull(Extractors.forPath(f.getKey())).extract(f.getKey(), f.getValue()));
        }
        String apiFile = GraphIds.file(API);
        String use = GraphIds.declared(USE, "Use");
        String sumOf = GraphIds.member(apiFile, "sumOf");
        fragments.add(new Fragment(
                List.of(new GraphNode(
                        sumOf, "sumOf()", "code", API, "L8", Map.of(Decl.KEY, Map.of(Decl.KIND, "function")))),
                List.of(
                        GraphEdge.of(apiFile, sumOf, Relations.CONTAINS, Confidence.EXTRACTED, API, 8),
                        GraphEdge.of(
                                GraphIds.member(use, "c"),
                                GraphIds.declared(API, "greet"),
                                Relations.CALLS,
                                Confidence.EXTRACTED,
                                USE,
                                6),
                        GraphEdge.of(
                                GraphIds.member(use, "e"), sumOf, Relations.CALLS, Confidence.EXTRACTED, USE, 8))));
        return new GraphTools(CodeGraph.merge(fragments), Scope.ALL, FILES::get, true);
    }

    @Test
    void a_top_level_function_is_named_by_its_file_its_facade_class_or_alone() {
        GraphTools tools = tools();
        String answer = tools.callers("Api.greet", 1, 2000);
        assertThat(answer)
                .startsWith("Usages of greet(): 1 in main sources, 0 in tests.")
                .contains("Complete from the compiled classes")
                .contains("Use.c()  src/main/kotlin/demo/Use.kt:6");
        assertThat(tools.callers("ApiKt.greet", 1, 2000)).isEqualTo(answer);
        assertThat(tools.callers("greet", 1, 2000)).isEqualTo(answer);
    }

    @Test
    void a_function_renamed_by_jvm_name_is_called_by_that_name() {
        assertThat(tools().callers("Api.total", 1, 2000))
                .startsWith("Usages of total(): 1 in main sources, 0 in tests.")
                .contains("Use.e()  src/main/kotlin/demo/Use.kt:8");
    }

    @Test
    void an_inline_function_is_never_complete_and_lists_its_calls_by_name() {
        assertThat(tools().callers("twice", 1, 2000))
                .startsWith("Usages of twice(): 0 in main sources, 0 in tests.")
                .contains("Not complete: twice() is a Kotlin inline function")
                .contains(
                        "inferred:\n  Use.a()  src/main/kotlin/demo/Use.kt:4 (inferred)\n      L4| fun a() = twice { 1 }")
                .doesNotContain("Nothing in the compiled classes calls it")
                .doesNotContain("Api.kt:3");
        assertThat(tools().callers("Account.audit", 1, 2000))
                .contains("Not complete: Account.audit() is a Kotlin inline function")
                .contains("Use.g()  src/main/kotlin/demo/Use.kt:11 (inferred)");
    }
}
