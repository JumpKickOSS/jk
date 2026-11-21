// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One rule judges a test class; a caller chooses only what a type it cannot read means. */
class TestClassRuleTest {

    private static final int ABSTRACT = 0x0400;
    private static final int ANNOTATION = 0x2000 | 0x0200 | ABSTRACT;

    private static TestClassRule.Type type(String name, int access, List<String> supertypes, List<String> members) {
        return new TestClassRule.Type(name, access, supertypes, List.of(), members);
    }

    @Test
    void an_unreadable_supertype_declares_tests_only_when_the_caller_guesses_so() {
        TestClassRule.Type child = type("a.Child", 1, List.of("lib.Base"), List.of());

        assertThat(TestClassRule.runsTests(child, List.of(), name -> null, true))
                .isTrue();
        assertThat(TestClassRule.runsTests(child, List.of(), name -> null, false))
                .isFalse();
    }

    @Test
    void a_platform_supertype_is_never_a_guess() {
        TestClassRule.Type plain = type("a.Plain", 1, List.of("java.lang.Object"), List.of());

        assertThat(TestClassRule.runsTests(plain, List.of(), name -> null, true))
                .isFalse();
    }

    @Test
    void only_an_annotation_type_is_followed_as_a_composed_annotation() {
        Map<String, TestClassRule.Type> types = Map.of(
                "a.Composed",
                new TestClassRule.Type(
                        "a.Composed", ANNOTATION, List.of(), List.of("org.junit.jupiter.api.Test"), List.of()),
                "a.NotAnAnnotation",
                new TestClassRule.Type(
                        "a.NotAnAnnotation", 1, List.of(), List.of("org.junit.jupiter.api.Test"), List.of()));

        assertThat(TestClassRule.runsTests(
                        type("a.UsesComposed", 1, List.of(), List.of("a.Composed")), List.of(), types::get, false))
                .isTrue();
        assertThat(TestClassRule.runsTests(
                        type("a.NamesAClass", 1, List.of(), List.of("a.NotAnAnnotation")),
                        List.of(),
                        types::get,
                        false))
                .isFalse();
    }

    @Test
    void an_abstract_class_runs_no_tests_but_its_concrete_outer_runs_its_nested_ones() {
        TestClassRule.Type nested = type("a.Outer$Inner", 1, List.of(), List.of("org.junit.jupiter.api.Test"));

        assertThat(TestClassRule.runsTests(
                        type("a.Base", ABSTRACT, List.of(), List.of("org.junit.jupiter.api.Test")),
                        List.of(),
                        name -> null,
                        true))
                .isFalse();
        assertThat(TestClassRule.runsTests(
                        type("a.Outer", 1, List.of(), List.of()), List.of(nested), name -> null, false))
                .isTrue();
    }
}
