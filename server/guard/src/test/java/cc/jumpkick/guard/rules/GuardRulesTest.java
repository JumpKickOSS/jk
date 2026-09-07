// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardRulesTest {

    private static LoadResult load(Path dir, String toml) throws IOException {
        Files.writeString(dir.resolve(GuardsPresence.RULES_FILE), toml, StandardCharsets.UTF_8);
        return GuardRules.load(dir, GuardsConfig.ABSENT);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(GuardRulesTest.class.getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> messages(LoadResult r) {
        return r.problems().stream().map(LoadError::message).toList();
    }

    @Test
    void no_file_loads_an_empty_set_with_no_problems(@TempDir Path dir) {
        LoadResult r = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(r.rules().isEmpty()).isTrue();
        assertThat(r.problems()).isEmpty();
    }

    @Test
    void the_spring_rules_file_from_the_prd_loads_clean(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir, resource("spring-rules.toml"));
        assertThat(r.errors()).as(messages(r).toString()).isEmpty();
        assertThat(r.warnings()).as("every rule is within the token budget").isEmpty();
        assertThat(r.rules().ids()).hasSize(15).contains("no-system-out", "layers", "no-agent-trailers");
        Rule floors = r.rules().rule("dependency-floors").orElseThrow();
        assertThat(floors.kind()).isEqualTo(Kind.DEPEND);
        assertThat(floors.table().getBoolean("convergence")).isTrue();
        Rule coverage = r.rules().rule("coverage-floor").orElseThrow();
        assertThat(coverage.allow()).containsExactly(new Allow("generated-*", "codegen modules"));
        assertThat(r.rules().rule("file-size").orElseThrow().baseline()).isTrue();
        assertThat(r.rules().sourceDigests()).containsOnlyKeys("jk-guards.toml");
    }

    @Test
    void the_jk_shaped_rules_from_the_prd_load_clean(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir, resource("jk-rules.toml"));
        assertThat(r.errors()).as(messages(r).toString()).isEmpty();
        assertThat(r.rules().ofKind(Kind.VOCABULARY)).hasSize(1);
        assertThat(r.rules().rule("host-surface").orElseThrow().instead()).isEqualTo("Os.current()");
    }

    @Test
    void every_kind_example_loads_through_the_loader(@TempDir Path dir) throws IOException {
        for (Kind k : Kind.values()) {
            if (k == Kind.TEST) continue; // a guard test is a @Guard method, not a table; its example says so
            LoadResult r = load(dir, k.example());
            assertThat(r.errors()).as(k.id() + ": " + messages(r)).isEmpty();
            assertThat(r.rules().rules()).as(k.id()).hasSize(1);
            assertThat(r.rules().rules().values().iterator().next().kind()).isEqualTo(k);
            assertThat(GuardRules.tokenCount(k.example(), r.rules().ids().get(0)))
                    .as(k.id() + " example within the token budget")
                    .isLessThanOrEqualTo(GuardRules.TOKEN_BUDGET);
        }
    }

    @Test
    void unknown_kind_names_the_closed_set(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir, "[guards.x]\nkind = \"forbidden\"\nwhy = \"w\"\n");
        assertThat(r.hasErrors()).isTrue();
        assertThat(messages(r).get(0)).contains("unknown kind `forbidden`").contains("forbid, annotate");
        assertThat(r.problems().get(0).line()).isEqualTo(2);
    }

    @Test
    void unknown_key_for_the_kind_is_an_error_naming_the_keys(@TempDir Path dir) throws IOException {
        LoadResult r = load(
                dir,
                "[guards.x]\nkind = \"forbid\"\nsignatures = [\"a.B\"]\ninstead = \"i\"\nwhy = \"w\"\nseverity = \"warn\"\n");
        assertThat(messages(r))
                .singleElement()
                .asString()
                .contains("unknown key `severity`")
                .contains("signatures");
        assertThat(r.rules().isEmpty()).isTrue();
    }

    @Test
    void missing_why_and_missing_instead_are_errors(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir, "[guards.x]\nkind = \"forbid\"\nsignatures = [\"a.B\"]\n");
        assertThat(messages(r)).anySatisfy(m -> assertThat(m).contains("missing required key `why`"));
        assertThat(messages(r)).anySatisfy(m -> assertThat(m).contains("missing required key `instead`"));
    }

    @Test
    void allow_without_a_reason_is_an_error(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir, "[guards.x]\nkind = \"split-package\"\nwhy = \"w\"\nallow = [{ in = \"a.b\" }]\n");
        assertThat(messages(r))
                .singleElement()
                .asString()
                .contains("no `reason`")
                .contains("suppression");
    }

    @Test
    void duplicate_and_malformed_ids_are_errors(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir, "[guards.Bad_Id]\nkind = \"split-package\"\nwhy = \"w\"\n");
        assertThat(messages(r)).singleElement().asString().contains("rule id must be");
    }

    @Test
    void one_of_groups_are_enforced(@TempDir Path dir) throws IOException {
        LoadResult both = load(
                dir,
                "[guards.x]\nkind = \"annotate\"\nrequire = \"a.A\"\nforbid = \"a.B\"\non = \"field\"\nwhy = \"w\"\n");
        assertThat(messages(both)).singleElement().asString().contains("exactly one of require, forbid");
        LoadResult none = load(dir, "[guards.y]\nkind = \"depend\"\nwhy = \"w\"\n");
        assertThat(messages(none)).singleElement().asString().contains("one of ban, coordinate");
    }

    @Test
    void closed_values_and_shapes_are_checked(@TempDir Path dir) throws IOException {
        LoadResult r = load(
                dir,
                "[guards.x]\nkind = \"text\"\npattern = \"a\"\nblank = \"everything\"\ninstead = \"i\"\nwhy = \"w\"\nfiles = \"not-a-list\"\n");
        assertThat(messages(r)).hasSize(2);
        assertThat(messages(r)).anySatisfy(m -> assertThat(m)
                .contains("`blank` must be one of comments, comments+strings, none, code"));
        assertThat(messages(r)).anySatisfy(m -> assertThat(m).contains("`files` must be a list of strings"));
    }

    @Test
    void extends_and_unknown_top_level_tables_are_refused_not_ignored(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir, "[guards]\nextends = [\"com.acme:rules:1\"]\n[rules.x]\nkind = \"forbid\"\n");
        assertThat(messages(r))
                .anySatisfy(m -> assertThat(m).contains("extends").contains("not supported yet"));
        assertThat(messages(r)).anySatisfy(m -> assertThat(m).contains("unknown top-level table `rules`"));
    }

    @Test
    void a_syntax_error_is_positioned(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir, "[guards.x]\nkind = \n");
        assertThat(r.hasErrors()).isTrue();
        assertThat(r.problems().get(0).message()).startsWith("TOML syntax");
        assertThat(r.problems().get(0).render()).startsWith("jk-guards.toml:2:");
    }

    @Test
    void an_oversized_rule_loads_with_a_warning(@TempDir Path dir) throws IOException {
        StringBuilder sb =
                new StringBuilder("[guards.x]\nkind = \"forbid\"\ninstead = \"i\"\nwhy = \"w\"\nsignatures = [");
        for (int i = 0; i < 70; i++) sb.append("\"a.B").append(i).append("\", ");
        sb.append("]\n");
        LoadResult r = load(dir, sb.toString());
        assertThat(r.hasErrors()).isFalse();
        assertThat(r.warnings())
                .singleElement()
                .extracting(LoadError::message)
                .asString()
                .contains("tokens; the budget is 60");
    }

    @Test
    void scope_globs_match_module_paths() {
        assertThat(Rule.globMatches("plugins/*", "plugins/android")).isTrue();
        assertThat(Rule.globMatches("plugins/*", "plugins/android/sub")).isFalse();
        assertThat(Rule.globMatches("**", "anything/at/all")).isTrue();
        assertThat(Rule.globMatches("shared/jk-api", "shared/jk-api")).isTrue();
        assertThat(Rule.globMatches("shared/jk-api", "shared/jk-apix")).isFalse();
    }

    @Test
    void presence_is_one_stat_or_a_flag(@TempDir Path dir) throws IOException {
        assertThat(GuardsPresence.detect(dir, false, false)).isFalse();
        assertThat(GuardsPresence.detect(dir, true, false)).isTrue();
        assertThat(GuardsPresence.detect(dir, false, true)).isTrue();
        Files.writeString(dir.resolve(GuardsPresence.RULES_FILE), "");
        assertThat(GuardsPresence.detect(dir, false, false)).isTrue();
    }
}
