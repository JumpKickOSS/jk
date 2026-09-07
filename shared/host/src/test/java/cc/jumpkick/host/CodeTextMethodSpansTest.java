// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CodeTextMethodSpansTest {

    static final String SOURCE = """
            package demo;

            import java.util.List;

            /** A type. { braces in comments do not count } */
            public class Demo {
                static final String TEXT = "a { brace in a string }";

                static {
                    System.out.println("initializer, not a member");
                }

                Demo(int seed) {
                    this.seed = seed;
                }

                public <T> List<T> twice(List<T> in, java.util.function.Function<T, T> f) throws Exception {
                    if (in.isEmpty()) {
                        return List.of();
                    }
                    for (T t : in) {
                        synchronized (this) {
                            f.apply(t);
                        }
                    }
                    Runnable r = () -> {
                        System.out.println("lambda lines belong to twice");
                    };
                    Object o = new Object() {
                        @Override
                        public String toString() {
                            return "anonymous method is not a member of Demo";
                        }
                    };
                    switch (in.size()) {
                        case 1 -> r.run();
                        default -> o.toString();
                    }
                    try {
                        r.run();
                    } catch (RuntimeException e) {
                        throw e;
                    }
                    return in;
                }

                record Point(int x, int y) {
                    Point {
                        if (x < 0) throw new IllegalArgumentException();
                    }

                    int sum() {
                        return x + y;
                    }
                }

                enum Kind {
                    A,
                    B;

                    boolean isA() {
                        return this == A;
                    }
                }

                private static void noArgs() {}
            }
            """;

    @Test
    void finds_methods_and_constructors_with_their_arity() {
        List<CodeText.MethodSpan> spans = CodeText.methodSpans(SOURCE);
        assertThat(spans)
                .extracting(s -> s.name() + "/" + s.arity())
                .containsExactlyInAnyOrder("Demo/1", "twice/2", "toString/0", "sum/0", "isA/0", "noArgs/0");
    }

    @Test
    void a_member_body_spans_its_braces_and_counts_nested_blocks() {
        CodeText.MethodSpan twice = CodeText.methodSpans(SOURCE).stream()
                .filter(s -> s.name().equals("twice"))
                .findFirst()
                .orElseThrow();
        String body = SOURCE.substring(twice.open(), twice.close() + 1);
        assertThat(body).startsWith("{").endsWith("}");
        assertThat(body).contains("lambda lines belong to twice").contains("anonymous method");
        // Every code line from the opening brace to the closing one, nested blocks included.
        assertThat(CodeText.codeLines(body, "java")).isEqualTo(29);
    }

    @Test
    void initializers_lambdas_control_blocks_and_compact_constructors_are_not_members() {
        List<String> names = CodeText.methodSpans(SOURCE).stream()
                .map(CodeText.MethodSpan::name)
                .toList();
        assertThat(names).doesNotContain("if", "for", "synchronized", "switch", "try", "catch", "static", "Point");
    }

    @Test
    void a_throws_clause_does_not_hide_the_parameter_list() {
        assertThat(CodeText.methodSpans("class A { void f(int a, int b) throws java.io.IOException, X<Y> { } }"))
                .extracting(s -> s.name() + "/" + s.arity())
                .containsExactly("f/2");
    }
}
