// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ClassFactsTest {

    @Test
    void names_come_in_binary_and_package_forms() {
        ClassFacts c = Fixtures.hashing();
        assertThat(c.name()).isEqualTo("a/b/Hashing");
        assertThat(c.binaryName()).isEqualTo("a.b.Hashing");
        assertThat(c.packageName()).isEqualTo("a.b");
        assertThat(c.sourceFile()).isEqualTo("Hashing.java");
    }

    @Test
    void a_default_package_class_has_an_empty_package_name() {
        ClassFacts c = Fixtures.cls("Main", List.of());
        assertThat(c.packageName()).isEmpty();
        assertThat(c.binaryName()).isEqualTo("Main");
        assertThat(c.sourceFile()).isNull();
    }

    @Test
    void package_info_is_recognised_in_a_package_and_in_the_default_package() {
        assertThat(Fixtures.packageInfo("a/b").isPackageInfo()).isTrue();
        assertThat(Fixtures.cls("package-info", List.of()).isPackageInfo()).isTrue();
        assertThat(Fixtures.hashing().isPackageInfo()).isFalse();
        assertThat(Fixtures.cls("a/b/MyPackageInfo", List.of()).isPackageInfo())
                .as("only the exact simple name counts")
                .isFalse();
    }

    @Test
    void nesting_is_a_dollar_anywhere_in_the_name() {
        assertThat(Fixtures.cls("a/b/Outer$Inner", List.of()).isNested()).isTrue();
        assertThat(Fixtures.cls("a/b/Outer$1", List.of()).isNested()).isTrue();
        assertThat(Fixtures.cls("a/b/Outer", List.of()).isNested()).isFalse();
    }

    @Test
    void annotations_are_looked_up_by_binary_type_name() {
        ClassFacts pi = Fixtures.packageInfo("a/b");
        assertThat(pi.hasAnnotation("org.jspecify.annotations.NullMarked")).isTrue();
        assertThat(pi.hasAnnotation("org/jspecify/annotations/NullMarked"))
                .as("internal names are not the lookup key")
                .isFalse();
        assertThat(pi.hasAnnotation("org.junit.jupiter.api.Tag")).isFalse();
    }

    @Test
    void access_flags_are_tested_by_mask() {
        ClassFacts pi = Fixtures.packageInfo("a/b");
        assertThat(pi.hasFlag(Fixtures.ACC_INTERFACE)).isTrue();
        assertThat(pi.hasFlag(Fixtures.ACC_PUBLIC)).isFalse();
        assertThat(Fixtures.hashing().hasFlag(Fixtures.ACC_PUBLIC)).isTrue();
    }

    @Test
    void collections_are_copied_and_type_refs_are_deduplicated() {
        ArrayList<String> ifaces = new ArrayList<>(List.of("java/io/Closeable"));
        HashSet<String> refs = new HashSet<>(List.of("x/Y", "x/Y", "a/B"));
        ClassFacts c =
                new ClassFacts("a/C", 1, "java/lang/Object", ifaces, null, List.of(), List.of(), List.of(), refs);
        ifaces.add("java/lang/Runnable");
        refs.add("z/Z");
        assertThat(c.interfaces()).containsExactly("java/io/Closeable");
        assertThat(c.typeRefs()).containsExactlyInAnyOrder("x/Y", "a/B");
        assertThatThrownBy(() -> c.interfaces().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.typeRefs().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void two_classes_with_the_same_facts_are_equal_whatever_the_input_collection_types() {
        ClassFacts a =
                new ClassFacts("a/C", 1, null, List.of(), null, List.of(), List.of(), List.of(), Set.of("q/R", "p/Q"));
        ClassFacts b = new ClassFacts(
                "a/C", 1, null, List.of(), null, List.of(), List.of(), List.of(), new TreeSet<>(List.of("p/Q", "q/R")));
        assertThat(a).isEqualTo(b);
        assertThat(a.superName()).isNull();
        assertThat(Map.of(a, 1)).containsKey(b);
    }
}
