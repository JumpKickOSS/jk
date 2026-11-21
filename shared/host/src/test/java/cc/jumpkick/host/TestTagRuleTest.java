// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestTagRuleTest {

    private static TestTagRule.Type type(
            String name, List<String> supertypes, Set<String> tags, Set<String> annotations) {
        return new TestTagRule.Type(name, supertypes, tags, annotations, Set.of(), Set.of());
    }

    private static TestTagRule.Types types(TestTagRule.Type... known) {
        Map<String, TestTagRule.Type> byName = new HashMap<>();
        for (TestTagRule.Type t : known) byName.put(t.name(), t);
        return byName::get;
    }

    @Test
    void a_composed_annotation_stands_for_its_tags_through_another_composed_one() {
        var slow = type("p.Slow", List.of(), Set.of("slow"), Set.of());
        var db = type("p.DbTest", List.of(), Set.of("db"), Set.of("p.Slow"));
        var test = type("p.RepoTest", List.of("java.lang.Object"), Set.of(), Set.of("p.DbTest"));

        assertThat(TestTagRule.classTags(test, types(slow, db))).containsExactlyInAnyOrder("db", "slow");
    }

    @Test
    void a_class_inherits_the_tags_of_its_superclasses_and_interfaces_but_not_of_what_cannot_be_read() {
        var base = type("p.Base", List.of("p.Missing"), Set.of("base"), Set.of());
        var contract = type("p.Contract", List.of(), Set.of("contract"), Set.of());
        var test = type("p.ChildTest", List.of("p.Base", "p.Contract"), Set.of("own"), Set.of("p.Unknown"));

        assertThat(TestTagRule.classTags(test, types(base, contract)))
                .containsExactlyInAnyOrder("own", "base", "contract");
    }

    @Test
    void member_tags_follow_composed_annotations_and_count_toward_the_class_tags() {
        var slow = type("p.Slow", List.of(), Set.of("slow"), Set.of());
        var test = new TestTagRule.Type(
                "p.MixedTest", List.of(), Set.of("fast"), Set.of(), Set.of("network"), Set.of("p.Slow"));

        assertThat(TestTagRule.classTags(test, types(slow))).containsExactly("fast");
        assertThat(TestTagRule.memberTags(test, types(slow))).containsExactlyInAnyOrder("network", "slow");
        assertThat(TestTagRule.tags(test, types(slow))).containsExactlyInAnyOrder("fast", "network", "slow");
    }

    @Test
    void an_annotation_cycle_ends() {
        var a = type("p.A", List.of(), Set.of("a"), Set.of("p.B"));
        var b = type("p.B", List.of(), Set.of("b"), Set.of("p.A"));
        var test = type("p.CycleTest", List.of(), Set.of(), Set.of("p.A"));

        assertThat(TestTagRule.classTags(test, types(a, b))).containsExactlyInAnyOrder("a", "b");
    }
}
