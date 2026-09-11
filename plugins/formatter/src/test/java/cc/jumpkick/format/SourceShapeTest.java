// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The post-mortem a timed-out file gets: the nesting shape that explains a formatter's line-break
 * search blowing up, and silence when the file's shape explains nothing. The same threshold decides
 * whether the run remembers the verdict.
 */
class SourceShapeTest {

    /** The shape that pegged a formatter for minutes: providers built from nested {@code flatMap}s. */
    private static final String NESTED_LAMBDAS = """
            class Providers {
                Arbitrary<Lockfile> lockfiles() {
                    return names().flatMap(name -> versions().flatMap(version -> hashes().flatMap(hash ->
                        sources().flatMap(source -> scopes().flatMap(scope -> classifiers().flatMap(classifier ->
                            Arbitraries.of(new Package(name, version, hash, source, scope, classifier))))))));
                }
            }
            """;

    @Test
    void deep_paren_nesting_is_measured_with_the_line_it_peaks_on() {
        SourceShape.Shape shape = SourceShape.of(NESTED_LAMBDAS);

        assertThat(shape.parenDepth()).isGreaterThanOrEqualTo(8);
        assertThat(shape.parenLine()).isEqualTo(5);
        assertThat(shape.lambdaNesting()).isEqualTo(6);
    }

    @Test
    void ordinary_source_is_shallow() {
        SourceShape.Shape shape = SourceShape.of("""
                class Ordinary {
                    int add(int a, int b) {
                        return Math.max(0, a + b);
                    }
                }
                """);

        assertThat(shape.parenDepth()).isEqualTo(1);
        assertThat(shape.lambdaNesting()).isZero();
    }

    @Test
    void parentheses_in_comments_and_string_literals_are_not_structure() {
        SourceShape.Shape shape = SourceShape.of("""
                class Quoted {
                    // ((((((((((((
                    String s = "((((((((((((";
                }
                """);

        assertThat(shape.parenDepth()).isZero();
    }

    @Test
    void the_post_mortem_names_the_nesting_and_the_line() {
        assertThat(SourceShape.postMortem(NESTED_LAMBDAS))
                .contains("deepest expression nesting here is")
                .contains("at line 5")
                .contains("6 nested lambdas")
                .contains("named locals or helper methods");
    }

    @Test
    void an_unremarkable_shape_gets_no_post_mortem() {
        assertThat(SourceShape.postMortem("class Ordinary {\n    int a = 1;\n}\n"))
                .isEmpty();
    }

    /**
     * The same threshold decides whether a timeout is <em>remembered</em>, so both sides of it are
     * pinned: nesting that explains a stall is worth recording, an ordinary file is not — recording
     * that would refuse a good file on every later run over a host that hiccuped once.
     */
    @Test
    void only_a_shape_that_explains_a_stall_is_worth_remembering() {
        assertThat(SourceShape.of(NESTED_LAMBDAS).explainsAStall()).isTrue();
        assertThat(SourceShape.of("class Ordinary {\n    int a = Math.max(1, 2);\n}\n")
                        .explainsAStall())
                .isFalse();
    }

    /**
     * Nesting means lambdas open inside one another. An arrow outside every parenthesis — a switch
     * arm, a lambda whose parameter list has already closed — never nests anything, and a decrement
     * beside a comparison is not an arrow at all.
     */
    @Test
    void only_arrows_inside_an_open_group_nest() {
        assertThat(SourceShape.of("class A { Object o = f(a -> g(b -> b)); }\n").lambdaNesting())
                .isEqualTo(2);
        assertThat(SourceShape.of("class A { int s = switch (x) { case 1 -> 2; default -> 3; };"
                                + " Object o = f(a -> g(b -> b)); }\n")
                        .lambdaNesting())
                .as("a switch arm at depth 0 must not add a permanent level")
                .isEqualTo(2);
        assertThat(SourceShape.of("class A { void m() { while (i-->0) {} } }\n").lambdaNesting())
                .isZero();
    }

    /** Deep parentheses alone are enough; so are nested lambdas alone. Either arm, not both. */
    @Test
    void either_kind_of_nesting_explains_a_stall_on_its_own() {
        assertThat(SourceShape.of("class A { Object o = a(b(c(d(e(f(g(h(1)))))))); }\n")
                        .explainsAStall())
                .as("8 parentheses deep, no lambdas")
                .isTrue();
        assertThat(SourceShape.of("class A { Object o = f(a -> g(b -> h(c -> c))); }\n")
                        .explainsAStall())
                .as("3 nested lambdas, only 3 parentheses deep")
                .isTrue();
    }
}
