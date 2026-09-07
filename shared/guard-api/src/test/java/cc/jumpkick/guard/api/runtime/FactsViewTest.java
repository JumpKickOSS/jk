// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.api.Annotated;
import cc.jumpkick.guard.api.FieldAccess;
import cc.jumpkick.guard.api.On;
import cc.jumpkick.guard.api.Sig;
import cc.jumpkick.guard.api.TaggedClass;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.MethodFacts;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FactsViewTest {
    private static final AnnotationFacts TEST = new AnnotationFacts("Lorg/junit/jupiter/api/Test;", true, Map.of());
    private static final AnnotationFacts JUNIT4 = new AnnotationFacts("Lorg/junit/Test;", true, Map.of());
    private static final AnnotationFacts NULL_MARKED =
            new AnnotationFacts("Lorg/jspecify/annotations/NullMarked;", false, Map.of());
    private static final AnnotationFacts PARAM =
            new AnnotationFacts("Lorg/jspecify/annotations/Nullable;", false, Map.of());

    private static AnnotationFacts tag(String v) {
        return new AnnotationFacts("Lorg/junit/jupiter/api/Tag;", true, Map.of("value", List.of(v)));
    }

    private static MethodFacts method(
            String name,
            String desc,
            List<AnnotationFacts> anns,
            List<List<AnnotationFacts>> params,
            List<CallSite> calls,
            List<FieldRef> refs) {
        return new MethodFacts(name, desc, 1, anns, params, calls, refs, 0, 3);
    }

    private static ClassFacts cls(
            String name,
            String source,
            List<AnnotationFacts> anns,
            List<FieldFacts> fields,
            List<MethodFacts> methods,
            Set<String> refs) {
        return new ClassFacts(name, 1, "java/lang/Object", List.of(), source, anns, fields, methods, refs);
    }

    private static FactsIndex index(ClassFacts... cs) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : cs) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "d");
    }

    private static FactsIndex main() {
        var replace = new CallSite("java/lang/String", "replace", "(CC)Ljava/lang/String;", 7, "\"", 1);
        var digest = new CallSite(
                "java/security/MessageDigest",
                "getInstance",
                "(Ljava/lang/String;)Ljava/security/MessageDigest;",
                9,
                "SHA-256",
                2);
        var read = new FieldRef("a/Esc", "count", "I", 8, false, 1);
        MethodFacts write = method(
                "write",
                "(Ljava/lang/String;I)V",
                List.of(),
                List.of(List.of(PARAM), List.of()),
                List.of(replace, digest),
                List.of(read));
        ClassFacts esc = cls(
                "a/Esc",
                "Esc.java",
                List.of(),
                List.of(
                        new FieldFacts("count", "I", 8, null, List.of()),
                        new FieldFacts("SEP", "Ljava/lang/String;", 25, ",", List.of(NULL_MARKED))),
                List.of(write),
                Set.of("b/Util"));
        ClassFacts pkg =
                cls("a/package-info", "package-info.java", List.of(NULL_MARKED), List.of(), List.of(), Set.of());
        ClassFacts util = cls("b/Util", "Util.java", List.of(), List.of(), List.of(), Set.of("a/Esc"));
        return index(esc, pkg, util);
    }

    private static FactsIndex test() {
        ClassFacts slow = cls(
                "a/EscTest",
                "EscTest.java",
                List.of(tag("slow")),
                List.of(),
                List.of(method("runs", "()V", List.of(TEST, tag("network")), List.of(), List.of(), List.of())),
                Set.of());
        ClassFacts legacy = cls(
                "a/OldTest",
                "OldTest.java",
                List.of(),
                List.of(),
                List.of(method("runs", "()V", List.of(JUNIT4), List.of(), List.of(), List.of())),
                Set.of());
        ClassFacts helper = cls(
                "a/Helper",
                "Helper.java",
                List.of(tag("slow")),
                List.of(),
                List.of(method("help", "()V", List.of(), List.of(), List.of(), List.of())),
                Set.of());
        ClassFacts pkg =
                cls("a/package-info", "package-info.java", List.of(NULL_MARKED), List.of(), List.of(), Set.of());
        return index(slow, legacy, helper, pkg);
    }

    private static FactsView view() {
        return new FactsView(main(), test(), List.of(Path.of("target/classes")));
    }

    @Test
    void classes_are_the_main_index_sorted_with_package_info_included() {
        assertThat(view().classes()).extracting(ClassFacts::name).containsExactly("a/Esc", "a/package-info", "b/Util");
    }

    @Test
    void calls_match_a_signature_and_carry_their_origin() {
        var replaces = view().calls(Sig.of("java.lang.String#replace(**)"));
        assertThat(replaces).hasSize(1);
        var s = replaces.get(0);
        assertThat(s.origin().cls().name()).isEqualTo("a/Esc");
        assertThat(s.origin().member()).isNotNull();
        assertThat(Objects.requireNonNull(s.origin().member()).name()).isEqualTo("write");
        assertThat(s.line()).isEqualTo(7);
        assertThat(s.file()).isEqualTo("a/Esc.java");
        assertThat(s.target()).isEqualTo("java.lang.String#replace");
        assertThat(s.nearbyLiteral("\"")).isTrue();
        assertThat(view().calls(Sig.of("java.security.**#getInstance(java.lang.String)")))
                .hasSize(1);
        assertThat(view().calls(Sig.of("java.security.**#getInstance()"))).isEmpty();
        assertThat(view().calls(Sig.of("x.Y#z"))).isEmpty();
    }

    @Test
    void field_refs_carry_their_origin_and_write_flag() {
        List<FieldAccess> refs = view().fieldRefs();
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).fingerprint()).isEqualTo("a.Esc#write(Ljava/lang/String;I)V -> a.Esc#count");
        assertThat(refs.get(0).line()).isEqualTo(8);
        assertThat(refs.get(0).file()).isEqualTo("a/Esc.java");
    }

    @Test
    void package_and_class_elements_partition_the_main_index() {
        List<Annotated> packages = view().annotations(On.PACKAGE);
        assertThat(packages).hasSize(1);
        assertThat(packages.get(0).cls().name()).isEqualTo("a/package-info");
        assertThat(packages.get(0).has("org.jspecify.annotations.NullMarked")).isTrue();
        assertThat(packages.get(0).parameter()).isEqualTo(-1);
        List<Annotated> classes = view().annotations(On.CLASS);
        assertThat(classes).extracting(a -> a.cls().name()).containsExactly("a/Esc", "b/Util");
        assertThat(classes.get(0).site().fingerprint()).isEqualTo("a.Esc");
    }

    @Test
    void method_field_and_parameter_elements_name_their_member() {
        List<Annotated> methods = view().annotations(On.METHOD);
        assertThat(methods).hasSize(1);
        assertThat(Objects.requireNonNull(methods.get(0).method()).name()).isEqualTo("write");
        assertThat(methods.get(0).field()).isNull();
        List<Annotated> fields = view().annotations(On.FIELD);
        assertThat(fields)
                .extracting(a -> Objects.requireNonNull(a.field()).name())
                .containsExactly("count", "SEP");
        assertThat(fields.get(1).has("org.jspecify.annotations.NullMarked")).isTrue();
        List<Annotated> params = view().annotations(On.PARAMETER);
        assertThat(params).hasSize(2);
        assertThat(params.get(0).parameter()).isEqualTo(0);
        assertThat(params.get(0).has("org.jspecify.annotations.Nullable")).isTrue();
        assertThat(params.get(1).parameter()).isEqualTo(1);
        assertThat(params.get(1).annotations()).isEmpty();
    }

    @Test
    void test_class_elements_come_from_the_test_index_and_need_a_test_method() {
        List<Annotated> tests = view().annotations(On.TEST_CLASS);
        assertThat(tests).extracting(a -> a.cls().name()).containsExactly("a/EscTest", "a/OldTest");
        assertThat(tests.get(0).has("org.junit.jupiter.api.Tag")).isTrue();
    }

    @Test
    void tagged_test_classes_collect_class_and_method_tags_and_skip_helpers() {
        List<TaggedClass> tagged = view().testClasses();
        assertThat(tagged).extracting(t -> t.cls().name()).containsExactly("a/EscTest", "a/OldTest");
        assertThat(tagged.get(0).tags()).containsExactlyInAnyOrder("slow", "network");
        assertThat(tagged.get(1).tags()).isEmpty();
        assertThat(tagged.get(0).fingerprint()).isEqualTo("a.EscTest");
        assertThat(tagged.get(0).file()).isEqualTo("a/EscTest.java");
    }

    @Test
    void a_class_is_a_test_class_by_any_supported_test_annotation() {
        assertThat(FactsView.isTestClass(test().classNamed("a/EscTest").orElseThrow()))
                .isTrue();
        assertThat(FactsView.isTestClass(test().classNamed("a/OldTest").orElseThrow()))
                .isTrue();
        assertThat(FactsView.isTestClass(test().classNamed("a/Helper").orElseThrow()))
                .isFalse();
        ClassFacts parameterized = cls(
                "a/P",
                "P.java",
                List.of(),
                List.of(),
                List.of(method(
                        "p",
                        "(I)V",
                        List.of(new AnnotationFacts("Lorg/junit/jupiter/params/ParameterizedTest;", true, Map.of())),
                        List.of(),
                        List.of(),
                        List.of())),
                Set.of());
        assertThat(FactsView.isTestClass(parameterized)).isTrue();
    }

    @Test
    void constants_edges_and_class_dirs_come_from_the_main_index_and_the_config() {
        assertThat(view().constants("a.Esc")).containsExactly(Map.entry("SEP", ","));
        assertThat(view().packageEdges()).containsExactly(Map.entry("a", Set.of("b")), Map.entry("b", Set.of("a")));
        assertThat(view().classDirs()).containsExactly(Path.of("target/classes"));
    }

    @Test
    void an_empty_test_index_yields_no_tagged_classes() {
        FactsView v = new FactsView(main(), FactsIndex.EMPTY, List.of());
        assertThat(v.testClasses()).isEmpty();
        assertThat(v.annotations(On.TEST_CLASS)).isEmpty();
        assertThat(v.classDirs()).isEmpty();
    }
}
