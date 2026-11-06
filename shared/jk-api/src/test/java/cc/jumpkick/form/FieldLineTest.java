// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** One field per line, as an agent or a {@code --help} reader types the answer. */
class FieldLineTest {

    @Test
    void each_kind_reads_as_the_values_it_takes_and_its_default() {
        assertThat(FieldLine.of(Field.of("database", "Database", FieldKind.CHOICE)
                        .option("none")
                        .option("postgres")
                        .defaultValue("postgres")
                        .build()))
                .isEqualTo("database: none|postgres = postgres  # Database");
        assertThat(FieldLine.of(Field.of("libs", "Libraries", FieldKind.MULTI)
                        .option("guava")
                        .option("jackson")
                        .defaultValue(List.of("guava"))
                        .build()))
                .isEqualTo("libs: [guava,jackson] = [guava]  # Libraries");
        assertThat(FieldLine.of(Field.of("native", "Native", FieldKind.BOOL)
                        .defaultValue(false)
                        .advanced(true)
                        .build()))
                .isEqualTo("native: true|false = false (advanced)  # Native");
        assertThat(FieldLine.of(Field.of("name", "Name", FieldKind.TEXT)
                        .pattern("[a-z]+")
                        .build()))
                .isEqualTo("name: text /[a-z]+/ (required)  # Name");
        assertThat(FieldLine.of(Field.of("group", "Group", FieldKind.TEXT)
                        .defaultFrom("git-email")
                        .build()))
                .isEqualTo("group: text = from git-email  # Group");
        assertThat(FieldLine.of(Field.of("cache", "Cache", FieldKind.BOOL)
                        .defaultValue(true)
                        .when("native")
                        .build()))
                .isEqualTo("cache: true|false = true when native  # Cache");
    }

    @Test
    void the_param_spelling_names_the_flag_that_answers_the_field() {
        Field choice = Field.of("database", "Database", FieldKind.CHOICE)
                .option("none")
                .option("mysql")
                .build();
        assertThat(FieldLine.param(choice)).isEqualTo("-p database=none|mysql");
        Field multi = Field.of("libs", "Libraries", FieldKind.MULTI)
                .option("a")
                .option("b")
                .build();
        assertThat(FieldLine.param(multi)).isEqualTo("-p libs=a,b");
        assertThat(FieldLine.param(Field.of("motto", "Motto", FieldKind.TEXT).build()))
                .isEqualTo("-p motto=…");
    }
}
