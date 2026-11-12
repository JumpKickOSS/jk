// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateAnswersTest {

    private static final JkTemplateToml.Meta META = JkTemplateToml.parse("""
            language = "java"
            framework = "spring-boot"
            name = "webmvc"

            [[ask]]
            key = "database"
            label = "Database"
            kind = "choice"
            options = ["none", "postgres", "mysql"]
            default = "postgres"

            [[ask]]
            key = "migrations"
            label = "Migrations"
            kind = "choice"
            options = ["flyway", "liquibase"]
            default = "flyway"
            when = "database != 'none'"

            [[ask]]
            key = "extras"
            label = "Extras"
            kind = "multi"
            options = ["actuator", "dev-tools"]
            default = []

            [[ask]]
            key = "native"
            label = "Native binary"
            kind = "bool"
            default = false

            [[ask]]
            key = "tagline"
            label = "Tagline"
            kind = "text"
            pattern = "[A-Za-z ]+"
            default = "Hello"
            """, "t.toml");

    @Test
    void defaults_bind_every_option_of_a_choice_and_bools_as_yes_or_no() {
        Map<String, @Nullable String> bound = TemplateAnswers.bind(META, Map.of("name", "demo"));
        assertThat(bound)
                .containsEntry("name", "demo")
                .containsEntry("database", "postgres")
                .containsEntry("database_postgres", "yes")
                .containsEntry("database_none", "no")
                .containsEntry("database_mysql", "no")
                .containsEntry("migrations_flyway", "yes")
                .containsEntry("extras", "")
                .containsEntry("extras_actuator", "no")
                .containsEntry("extras_dev_tools", "no")
                .containsEntry("native", "no")
                .containsEntry("tagline", "Hello");
    }

    @Test
    void answers_from_flags_are_typed_and_bound() {
        Map<String, @Nullable String> bound = TemplateAnswers.bind(
                META,
                Map.of("database", "mysql", "extras", "dev-tools,actuator", "native", "yes", "tagline", "Hi there"));
        assertThat(bound)
                .containsEntry("database_mysql", "yes")
                .containsEntry("database_postgres", "no")
                .containsEntry("extras", "actuator,dev-tools")
                .containsEntry("extras_actuator", "yes")
                .containsEntry("extras_dev_tools", "yes")
                .containsEntry("native", "yes")
                .containsEntry("tagline", "Hi there");
        assertThat(TemplateAnswers.bind(META, Map.of("native", "false"))).containsEntry("native", "no");
    }

    @Test
    void a_hidden_ask_takes_its_default() {
        Map<String, @Nullable String> bound =
                TemplateAnswers.bind(META, Map.of("database", "none", "migrations", "liquibase"));
        assertThat(bound).containsEntry("database_none", "yes").containsEntry("migrations", "flyway");
    }

    @Test
    void an_answer_that_does_not_fit_is_refused_naming_the_ask_and_its_flag() {
        assertThatThrownBy(() -> TemplateAnswers.bind(META, Map.of("database", "oracle")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database must be one of none, postgres, mysql; got 'oracle'")
                .hasMessageContaining("-p database=none|postgres|mysql");
        assertThatThrownBy(() -> TemplateAnswers.bind(META, Map.of("native", "maybe")))
                .hasMessageContaining("native must be true or false")
                .hasMessageContaining("-p native=true|false");
        assertThatThrownBy(() -> TemplateAnswers.bind(META, Map.of("extras", "actuator,jmx")))
                .hasMessageContaining("extras must be one of actuator, dev-tools; got 'jmx'");
        assertThatThrownBy(() -> TemplateAnswers.bind(META, Map.of("tagline", "1")))
                .hasMessageContaining("tagline must match");
    }

    @Test
    void a_required_ask_left_unanswered_is_refused() {
        JkTemplateToml.Meta meta = JkTemplateToml.parse("""
                language = "java"
                name = "x"

                [[ask]]
                key = "region"
                label = "Region"
                kind = "choice"
                options = ["eu", "us"]
                """, "t.toml");
        assertThatThrownBy(() -> TemplateAnswers.bind(meta, Map.of()))
                .hasMessageContaining("region needs a value")
                .hasMessageContaining("-p region=eu|us");
        assertThat(TemplateAnswers.bind(meta, Map.of("region", "eu"))).containsEntry("region_eu", "yes");
    }

    @Test
    void a_hidden_ask_with_no_default_is_unset_and_its_guard_is_false(@TempDir Path temp) throws Exception {
        JkTemplateToml.Meta meta = JkTemplateToml.parse("""
                language = "java"
                name = "x"

                [[ask]]
                key = "database"
                label = "Database"
                kind = "choice"
                options = ["none", "postgres"]
                default = "none"

                [[ask]]
                key = "db_name"
                label = "Database name"
                kind = "text"
                when = "database != 'none'"

                [[ask]]
                key = "engine"
                label = "Engine"
                kind = "choice"
                options = ["fast", "safe"]
                when = "database != 'none'"
                """, "t.toml");
        Map<String, @Nullable String> bound = TemplateAnswers.bind(meta, Map.of("name", "demo"));
        assertThat(bound)
                .containsEntry("db_name", null)
                .containsEntry("engine", null)
                .containsEntry("engine_fast", "no")
                .containsEntry("engine_safe", "no");

        Path g8 = temp.resolve("t.g8/src/main/g8");
        Files.createDirectories(g8);
        Files.writeString(g8.resolve("jk.toml"), """
                name = "$name$"
                $if(db_name)$
                db = "$db_name$"
                $endif$
                $if(engine_fast.truthy)$
                fast = true
                $endif$
                """);
        Path dest = temp.resolve("out");
        Giter8Apply.apply(temp.resolve("t.g8"), dest, bound);
        assertThat(dest.resolve("jk.toml")).content().isEqualTo("name = \"demo\"\n");

        Path shown = temp.resolve("shown");
        Giter8Apply.apply(
                temp.resolve("t.g8"),
                shown,
                TemplateAnswers.bind(
                        meta, Map.of("name", "demo", "database", "postgres", "db_name", "app", "engine", "fast")));
        assertThat(shown.resolve("jk.toml")).content().isEqualTo("name = \"demo\"\ndb = \"app\"\nfast = true\n");
    }

    @Test
    void a_template_with_no_descriptor_passes_params_through() {
        assertThat(TemplateAnswers.bind(null, Map.of("anything", "goes")))
                .containsExactly(Map.entry("anything", "goes"));
    }

    @Test
    void bindings_drive_giter8_conditionals(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("t.g8/src/main/g8");
        Files.createDirectories(g8);
        Files.writeString(g8.resolve("jk.toml"), """
                name = "$name$"
                $if(database_postgres.truthy)$
                postgresql = "42"
                $endif$
                $if(native.truthy)$
                [native]
                $endif$
                """);
        Path dest = temp.resolve("out");
        Giter8Apply.apply(
                temp.resolve("t.g8"), dest, TemplateAnswers.bind(META, Map.of("name", "demo", "native", "true")));
        assertThat(dest.resolve("jk.toml")).content().isEqualTo("""
                name = "demo"
                postgresql = "42"
                [native]
                """);
    }
}
