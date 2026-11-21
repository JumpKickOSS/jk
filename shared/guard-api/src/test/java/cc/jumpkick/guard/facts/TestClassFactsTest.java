// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A class is a test class when the JUnit Platform would find tests in it, read from its facts. */
class TestClassFactsTest {

    private static final int PUBLIC = 0x0001;
    private static final int INTERFACE = 0x0200 | 0x0400;
    private static final int ABSTRACT = 0x0400;
    private static final int ANNOTATION = 0x2000 | INTERFACE;

    private static AnnotationFacts ann(String binaryName) {
        return new AnnotationFacts("L" + binaryName.replace('.', '/') + ";", true, Map.of());
    }

    private static MethodFacts method(AnnotationFacts... anns) {
        return new MethodFacts("m", "()V", PUBLIC, List.of(anns), List.of(), List.of(), List.of(), 0, 1);
    }

    private static ClassFacts cls(
            String name,
            int access,
            String superName,
            List<String> interfaces,
            List<AnnotationFacts> anns,
            MethodFacts... methods) {
        return new ClassFacts(name, access, superName, interfaces, null, anns, List.of(), List.of(methods), Set.of());
    }

    private static ClassFacts plain(String name, String superName, MethodFacts... methods) {
        return cls(name, PUBLIC, superName, List.of(), List.of(), methods);
    }

    private static FactsIndex index(ClassFacts... cs) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : cs) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "d");
    }

    private static boolean isTest(FactsIndex test, String name) {
        return new TestClassFacts(List.of(test))
                .isTestClass(test.classNamed(name).orElseThrow());
    }

    @Test
    void a_test_method_of_any_supported_framework_makes_a_test_class() {
        FactsIndex test = index(
                plain("a/Jupiter", "java/lang/Object", method(ann("org.junit.jupiter.api.Test"))),
                plain("a/Junit4", "java/lang/Object", method(ann("org.junit.Test"))),
                plain("a/TestNg", "java/lang/Object", method(ann("org.testng.annotations.Test"))),
                plain("a/Param", "java/lang/Object", method(ann("org.junit.jupiter.params.ParameterizedTest"))),
                plain("a/Helper", "java/lang/Object", method(ann("org.junit.jupiter.api.BeforeEach"))));

        assertThat(isTest(test, "a/Jupiter")).isTrue();
        assertThat(isTest(test, "a/Junit4")).isTrue();
        assertThat(isTest(test, "a/TestNg")).isTrue();
        assertThat(isTest(test, "a/Param")).isTrue();
        assertThat(isTest(test, "a/Helper")).isFalse();
    }

    @Test
    void a_class_inheriting_its_tests_is_a_test_class_and_its_abstract_base_is_not() {
        FactsIndex test = index(
                cls(
                        "a/Base",
                        PUBLIC | ABSTRACT,
                        "java/lang/Object",
                        List.of(),
                        List.of(),
                        method(ann("org.junit.jupiter.api.Test"))),
                plain("a/Child", "a/Base"),
                cls(
                        "a/Contract",
                        INTERFACE,
                        "java/lang/Object",
                        List.of(),
                        List.of(),
                        method(ann("org.junit.jupiter.api.Test"))),
                cls("a/Implementer", PUBLIC, "java/lang/Object", List.of("a/Contract"), List.of()));

        assertThat(isTest(test, "a/Child")).isTrue();
        assertThat(isTest(test, "a/Implementer")).isTrue();
        assertThat(isTest(test, "a/Base")).isFalse();
        assertThat(isTest(test, "a/Contract")).isFalse();
    }

    @Test
    void a_base_in_the_main_index_is_read_from_it() {
        FactsIndex main = index(cls(
                "a/Base",
                PUBLIC | ABSTRACT,
                "java/lang/Object",
                List.of(),
                List.of(),
                method(ann("org.junit.jupiter.api.Test"))));
        FactsIndex test = index(plain("a/Child", "a/Base"));

        assertThat(new TestClassFacts(List.of(test, main))
                        .isTestClass(test.classNamed("a/Child").orElseThrow()))
                .isTrue();
    }

    @Test
    void an_outer_class_whose_tests_are_all_nested_is_a_test_class() {
        FactsIndex test = index(
                plain("a/Outer", "java/lang/Object"),
                cls(
                        "a/Outer$Inner",
                        PUBLIC,
                        "java/lang/Object",
                        List.of(),
                        List.of(ann("org.junit.jupiter.api.Nested")),
                        method(ann("org.junit.jupiter.api.Test"))));

        assertThat(isTest(test, "a/Outer")).isTrue();
        assertThat(isTest(test, "a/Outer$Inner")).isTrue();
    }

    @Test
    void a_specification_with_no_annotation_is_a_test_class() {
        FactsIndex test = index(
                plain("a/SpockSpec", "spock/lang/Specification"),
                plain("a/KotestSpec", "io/kotest/core/spec/style/FunSpec"),
                plain("a/Junit3", "junit/framework/TestCase"));

        assertThat(isTest(test, "a/SpockSpec")).isTrue();
        assertThat(isTest(test, "a/KotestSpec")).isTrue();
        assertThat(isTest(test, "a/Junit3")).isTrue();
    }

    @Test
    void a_composed_test_annotation_makes_a_test_class() {
        FactsIndex test = index(
                cls(
                        "a/IntegrationTest",
                        ANNOTATION,
                        "java/lang/Object",
                        List.of("java/lang/annotation/Annotation"),
                        List.of(ann("org.junit.jupiter.api.Test"))),
                plain("a/UsesComposed", "java/lang/Object", method(ann("a.IntegrationTest"))),
                cls("a/Marker", ANNOTATION, "java/lang/Object", List.of("java/lang/annotation/Annotation"), List.of()),
                plain("a/UsesMarker", "java/lang/Object", method(ann("a.Marker"))));

        assertThat(isTest(test, "a/UsesComposed")).isTrue();
        assertThat(isTest(test, "a/UsesMarker")).isFalse();
    }

    @Test
    void a_type_no_index_holds_is_read_from_outside_and_otherwise_declares_no_test() {
        FactsIndex test = index(plain("a/Child", "lib/Base"));
        ClassFacts libBase = cls(
                "lib/Base",
                PUBLIC | ABSTRACT,
                "java/lang/Object",
                List.of(),
                List.of(),
                method(ann("org.junit.jupiter.api.Test")));
        ClassFacts child = test.classNamed("a/Child").orElseThrow();

        assertThat(new TestClassFacts(List.of(test)).isTestClass(child)).isFalse();
        assertThat(new TestClassFacts(
                                List.of(test), n -> n.equals("lib/Base") ? Optional.of(libBase) : Optional.empty())
                        .isTestClass(child))
                .isTrue();
    }
}
