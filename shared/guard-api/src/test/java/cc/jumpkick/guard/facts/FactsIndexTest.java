// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FactsIndexTest {

    private static FactsIndex two() {
        return Fixtures.index(Fixtures.util(), Fixtures.hashing(), Fixtures.packageInfo("a/b"));
    }

    @Test
    void classes_are_sorted_by_internal_name_however_they_were_given() {
        assertThat(two().classList())
                .extracting(ClassFacts::name)
                .containsExactly("a/b/Hashing", "a/b/package-info", "a/c/Util");
        assertThat(two().classes().keySet()).containsExactly("a/b/Hashing", "a/b/package-info", "a/c/Util");
        assertThatThrownBy(() -> two().classes().put("x", Fixtures.util()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void a_class_is_found_by_internal_name_only() {
        assertThat(two().classNamed("a/b/Hashing")).isPresent();
        assertThat(two().classNamed("a.b.Hashing")).isEmpty();
        assertThat(two().classNamed("a/b/Missing")).isEmpty();
    }

    @Test
    void classes_where_filters_in_sorted_order() {
        assertThat(two().classesWhere(c -> c.packageName().equals("a.b")))
                .extracting(ClassFacts::name)
                .containsExactly("a/b/Hashing", "a/b/package-info");
        assertThat(two().classesWhere(ClassFacts::isPackageInfo)).hasSize(1);
        assertThat(two().classesWhere(c -> false)).isEmpty();
    }

    @Test
    void every_call_and_field_reference_is_listed_with_its_origin() {
        List<FactsIndex.OriginCall> calls = two().calls();
        assertThat(calls).hasSize(1);
        FactsIndex.OriginCall c = calls.get(0);
        assertThat(c.origin().name()).isEqualTo("a/b/Hashing");
        assertThat(c.member().name()).isEqualTo("newDigest");
        assertThat(c.site().literalBefore()).isEqualTo("SHA-256");
        assertThat(c.fingerprint())
                .isEqualTo(
                        "a.b.Hashing#newDigest()Ljava/security/MessageDigest;"
                                + " -> java.security.MessageDigest#getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;");
        List<FactsIndex.OriginFieldRef> refs = two().fieldRefs();
        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).fingerprint())
                .isEqualTo("a.b.Hashing#newDigest()Ljava/security/MessageDigest; -> a.b.Hashing#calls");
        assertThat(refs.get(1).ref().write()).isTrue();
    }

    @Test
    void origin_fingerprints_fold_synthetic_ordinals() {
        MethodFacts lambda =
                Fixtures.method("lambda$run$4", "()V", List.of(new CallSite("x/Y", "g", "()V", 9, null, 1)), List.of());
        FactsIndex idx = Fixtures.index(Fixtures.cls("a/B$2", List.of(lambda)));
        assertThat(idx.calls().get(0).fingerprint()).isEqualTo("a.B$#lambda$run$()V -> x.Y#g()V");
    }

    @Test
    void constants_are_the_fields_with_a_constant_value_keyed_by_the_binary_owner() {
        assertThat(two().constants("a.b.Hashing")).containsExactly(Map.entry("ALGORITHM", "SHA-256"));
        assertThat(two().constants("a/b/Hashing"))
                .as("an internal-name spelling is normalised, not rejected")
                .containsExactly(Map.entry("ALGORITHM", "SHA-256"));
        assertThat(two().constants("a.b.Missing")).isEmpty();
        assertThat(two().constants("a.c.Util")).isEmpty();
    }

    @Test
    void package_edges_stay_inside_the_index_and_never_point_at_the_package_itself() {
        Map<String, Set<String>> edges = two().packageEdges();
        assertThat(edges.keySet()).containsExactly("a.b", "a.c");
        assertThat(edges.get("a.b"))
                .as("java.* is not in the index; a/b/Hashing → a/c/Util is")
                .containsExactly("a.c");
        assertThat(edges.get("a.c")).isEmpty();
    }

    @Test
    void a_reference_to_a_sibling_class_in_the_same_package_is_not_an_edge() {
        ClassFacts a = Fixtures.cls("p/A", List.of(), List.of(), Set.of("p/B", "q/C"));
        ClassFacts b = Fixtures.cls("p/B", List.of(), List.of(), Set.of("p/A"));
        ClassFacts c = Fixtures.cls("q/C", List.of(), List.of(), Set.of());
        Map<String, Set<String>> edges = Fixtures.index(a, b, c).packageEdges();
        assertThat(edges.get("p")).containsExactly("q");
        assertThat(edges.get("q")).isEmpty();
    }

    @Test
    void packages_are_the_sorted_set_of_declared_packages() {
        assertThat(two().packages()).containsExactly("a.b", "a.c");
        assertThat(FactsIndex.EMPTY.packages()).isEmpty();
        assertThat(Fixtures.index(Fixtures.cls("Main", List.of())).packages()).containsExactly("");
    }

    @Test
    void with_stamps_keeps_the_classes_and_replaces_the_change_detector() {
        FactsIndex restamped = two().withStamps(Map.of("z.class", "9:9"), "d2");
        assertThat(restamped.classes()).isEqualTo(two().classes());
        assertThat(restamped.stamps()).containsExactly(Map.entry("z.class", "9:9"));
        assertThat(restamped.bodyDigest()).isEqualTo("d2");
        assertThat(two().stamps()).containsExactly(Map.entry("a/b/Hashing.class", "1200:1"));
    }

    @Test
    void the_empty_index_answers_every_query_with_nothing() {
        assertThat(FactsIndex.EMPTY.classList()).isEmpty();
        assertThat(FactsIndex.EMPTY.calls()).isEmpty();
        assertThat(FactsIndex.EMPTY.fieldRefs()).isEmpty();
        assertThat(FactsIndex.EMPTY.packageEdges()).isEmpty();
        assertThat(FactsIndex.EMPTY.constants("a.B")).isEmpty();
        assertThat(FactsIndex.EMPTY.bodyDigest()).isEmpty();
    }
}
