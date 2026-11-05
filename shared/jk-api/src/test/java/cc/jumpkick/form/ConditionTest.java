// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code when} language: what parses, what it means, and what a form refuses at build time. */
class ConditionTest {

    private static final Field DATABASE = Field.of("database", "Database", FieldKind.CHOICE)
            .option("none")
            .option("postgres")
            .option("mysql")
            .defaultValue("postgres")
            .build();
    private static final Field NATIVE =
            Field.of("native", "Native", FieldKind.BOOL).defaultValue(false).build();
    private static final Field FEATURES = Field.of("features", "Features", FieldKind.MULTI)
            .option("web")
            .option("cli")
            .option("db")
            .build();
    private static final Field NAME = Field.of("name", "Name", FieldKind.TEXT).build();

    @Test
    void comparisons_and_in_test_the_settled_values() {
        Map<String, Object> values = Map.of("database", "postgres", "native", true, "features", List.of("web", "db"));

        assertThat(Condition.parse("database == 'postgres'").test(values)).isTrue();
        assertThat(Condition.parse("database != \"postgres\"").test(values)).isFalse();
        assertThat(Condition.parse("database in ['mysql', 'postgres']").test(values))
                .isTrue();
        assertThat(Condition.parse("features in ['cli']").test(values)).isFalse();
        assertThat(Condition.parse("features in ['cli', 'db']").test(values)).isTrue();
        assertThat(Condition.parse("native").test(values)).isTrue();
        assertThat(Condition.parse("native == false").test(values)).isFalse();
    }

    @Test
    void and_binds_tighter_than_or_and_parentheses_and_not_regroup() {
        Map<String, Object> values = Map.of("database", "none", "native", true);

        assertThat(Condition.parse("database == 'none' || database == 'mysql' && !native")
                        .test(values))
                .isTrue();
        assertThat(Condition.parse("(database == 'none' || database == 'mysql') && !native")
                        .test(values))
                .isFalse();
        assertThat(Condition.parse("!(database == 'mysql')").test(values)).isTrue();
        assertThat(Condition.parse("!!native").test(values)).isTrue();
    }

    @Test
    void a_key_with_no_value_is_unequal_to_every_literal() {
        Map<String, Object> none = Map.of();

        assertThat(Condition.parse("database == 'none'").test(none)).isFalse();
        assertThat(Condition.parse("database != 'none'").test(none)).isTrue();
        assertThat(Condition.parse("database in ['none']").test(none)).isFalse();
        assertThat(Condition.parse("native").test(none)).isFalse();
    }

    @Test
    void keys_lists_every_name_in_order() {
        assertThat(Condition.parse("b == 'x' && (a || !c in ['y'])").keys()).containsExactly("b", "a", "c");
    }

    @Test
    void syntax_errors_name_the_column() {
        assertThatThrownBy(() -> Condition.parse("database = 'none'"))
                .isInstanceOf(FormException.class)
                .hasMessageContaining("which is not part of the language")
                .hasMessageContaining("column 10");
        assertThatThrownBy(() -> Condition.parse("database == none"))
                .hasMessageContaining("expects a quoted string, true or false, got 'none' (column 13)");
        assertThatThrownBy(() -> Condition.parse("database == 'none"))
                .hasMessageContaining("unclosed string (column 13)");
        assertThatThrownBy(() -> Condition.parse("(native")).hasMessageContaining("expects ')', got the end");
        assertThatThrownBy(() -> Condition.parse("native native")).hasMessageContaining("where it should end");
        assertThatThrownBy(() -> Condition.parse("database in []")).hasMessageContaining("expects a quoted string");
        assertThatThrownBy(() -> Condition.parse("  ")).hasMessageContaining("is empty");
        assertThatThrownBy(() -> Condition.parse("lower(name) == 'x'")).hasMessageContaining("'('");
    }

    @Test
    void a_condition_on_a_later_field_is_refused() {
        Field migrations = Field.of("migrations", "Migrations", FieldKind.CHOICE)
                .option("flyway")
                .when("database != 'none'")
                .build();

        assertThatThrownBy(() -> form(migrations, DATABASE))
                .isInstanceOf(FormException.class)
                .hasMessage("when of 'migrations' (database != 'none') names 'database', which comes after it;"
                        + " a condition names earlier fields only");
    }

    @Test
    void a_condition_on_a_field_in_an_earlier_step_is_allowed() {
        Field migrations = Field.of("migrations", "Migrations", FieldKind.CHOICE)
                .option("flyway")
                .defaultValue("flyway")
                .when("database != 'none'")
                .build();

        Form form = Form.of("f", "F", Step.of("one", "One", DATABASE), Step.of("two", "Two", migrations));

        assertThat(form.field("migrations")).isNotNull();
    }

    @Test
    void unknown_and_self_references_are_refused() {
        assertThatThrownBy(() -> form(when(NAME, "port == 'x'")))
                .hasMessageContaining("names 'port', which is not a field of this form");
        assertThatThrownBy(
                        () -> form(Field.of("x", "X", FieldKind.BOOL).when("x").build()))
                .hasMessageContaining("names the field itself");
    }

    @Test
    void literals_must_suit_the_field_they_test() {
        assertThatThrownBy(() -> form(DATABASE, when(NAME, "database == 'mongo'")))
                .hasMessageContaining("'mongo' is not an option of 'database' (none, postgres, mysql)");
        assertThatThrownBy(() -> form(NATIVE, when(NAME, "native == 'yes'")))
                .hasMessageContaining("compares bool field 'native' with 'yes'; use true or false");
        assertThatThrownBy(() -> form(DATABASE, when(NAME, "database == true")))
                .hasMessageContaining("use a quoted string");
        assertThatThrownBy(() -> form(DATABASE, when(NAME, "database")))
                .hasMessageContaining("uses choice field 'database' as a boolean");
        assertThatThrownBy(() -> form(NATIVE, when(NAME, "native in [true]")))
                .hasMessageContaining("tests bool field 'native' with in");
        assertThatThrownBy(() -> form(FEATURES, when(NAME, "features == 'web'")))
                .hasMessageContaining("compares multi field 'features'; test it with in [...]");
        assertThatThrownBy(() -> form(FEATURES, when(NAME, "features in ['gui']")))
                .hasMessageContaining("'gui' is not an option of 'features'");
    }

    private static Field when(Field f, String condition) {
        return Field.of(f.key(), f.label(), f.kind()).when(condition).build();
    }

    private static Form form(Field... fields) {
        return Form.of("f", "F", Step.of("one", "One", fields));
    }
}
