// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.archunit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class JkViolationStoreTest {

    private static final String BASELINE = """
            # jk-guards-baseline.toml
            [blind-tree-walks]
            population = { units = 3 }
            [[blind-tree-walks.entries]]
            in     = "plugins/quarkus"
            at     = "cc.jumpkick.quarkus.Q#f()V -> java.nio.file.Files#walk(Ljava/nio/file/Path;)Ljava/util/stream/Stream;"
            reason = "measured"
            [[blind-tree-walks.entries]]
            in     = "server/engine"
            at     = "Method <a.B.f()> calls method <x.Y.g()> in (B.java)"
            reason = "measured"
            [[other-rule.entries]]
            at     = "not this one"
            [[arch-layers.entries]]
            at     = "a \\"quoted\\" line with a back\\\\slash"
            reason = "escapes"
            """;

    @Test
    void the_at_values_of_the_named_rule_are_the_known_violations() {
        List<String> entries = JkViolationStore.entriesFor(BASELINE, "blind-tree-walks");
        assertThat(entries)
                .containsExactly(
                        "cc.jumpkick.quarkus.Q#f()V -> java.nio.file.Files#walk(Ljava/nio/file/Path;)Ljava/util/stream/Stream;",
                        "Method <a.B.f()> calls method <x.Y.g()> in (B.java)");
    }

    @Test
    void other_rules_entries_are_not_mixed_in_and_an_unknown_rule_has_none() {
        assertThat(JkViolationStore.entriesFor(BASELINE, "other-rule")).containsExactly("not this one");
        assertThat(JkViolationStore.entriesFor(BASELINE, "blind"))
                .as("the id is quoted, not a prefix")
                .isEmpty();
        assertThat(JkViolationStore.entriesFor(BASELINE, "nothing-here")).isEmpty();
        assertThat(JkViolationStore.entriesFor("", "x")).isEmpty();
    }

    @Test
    void toml_escapes_in_the_at_value_are_undone() {
        assertThat(JkViolationStore.entriesFor(BASELINE, "arch-layers"))
                .containsExactly("a \"quoted\" line with a back\\slash");
    }

    @Test
    void a_rule_id_with_regex_metacharacters_is_matched_literally() {
        String toml = "[[a.b+c.entries]]\nat = \"x\"\n[[aXb+c.entries]]\nat = \"y\"\n";
        assertThat(JkViolationStore.entriesFor(toml, "a.b+c")).containsExactly("x");
    }

    @Test
    void the_store_is_read_only_and_answers_every_rule() {
        JkViolationStore store = new JkViolationStore();
        store.initialize(new Properties());
        ArchRule rule = ArchRuleDefinition.classes().should().bePublic();
        assertThat(store.contains(rule)).isTrue();
        store.save(rule, List.of("ignored"));
        assertThat(store.getViolations(rule))
                .as("no guard is running on this thread")
                .isEmpty();
    }
}
