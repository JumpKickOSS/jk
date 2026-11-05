// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.form.FormCommand.Flag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The summary's equivalent command: flags for mapped fields, {@code -p} for the rest, defaults left out. */
class FormCommandTest {

    private static final Map<String, Flag> FLAGS = Map.of(
            "lang", Flag.named("--lang"),
            "name", Flag.positional(),
            "template", Flag.named("-t"),
            "group", Flag.named("--group"),
            "native", Flag.named("--native"));

    @Test
    void mapped_fields_take_their_flag_and_the_rest_are_params() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("lang", "kotlin");
        answers.put("name", "demo");
        answers.put("template", "spring-boot/webmvc");
        answers.put("database", "mysql");
        answers.put("features", List.of("cli", "web"));

        String command = FormCommand.render("jk new", FormTest.sample(), answers, FLAGS, DerivedDefaults.NONE);

        assertThat(command)
                .isEqualTo("jk new demo --lang kotlin -t spring-boot/webmvc -p database=mysql -p features=web,cli");
    }

    @Test
    void defaults_and_hidden_fields_are_left_out() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("lang", "java");
        answers.put("name", "demo");
        answers.put("template", "t");
        answers.put("group", "com.example");
        answers.put("database", "none");
        answers.put("migrations", "liquibase");

        String command = FormCommand.render("jk new", FormTest.sample(), answers, FLAGS, from -> "com.example");

        assertThat(command).isEqualTo("jk new demo -t t -p database=none");
    }

    @Test
    void a_bool_flag_is_bare_when_true_and_spelled_false_otherwise() {
        Field on =
                Field.of("native", "Native", FieldKind.BOOL).defaultValue(false).build();
        Field off =
                Field.of("tests", "Tests", FieldKind.BOOL).defaultValue(true).build();
        Form form = Form.of("f", "F", Step.of("s", "S", on, off));
        Map<String, Flag> flags = Map.of("native", Flag.named("--native"), "tests", Flag.named("--tests"));

        assertThat(FormCommand.render(
                        "jk x", form, Map.of("native", true, "tests", false), flags, DerivedDefaults.NONE))
                .isEqualTo("jk x --native --tests=false");
        assertThat(FormCommand.render(
                        "jk x", form, Map.of("native", true, "tests", false), Map.of(), DerivedDefaults.NONE))
                .isEqualTo("jk x -p native=true -p tests=false");
    }

    @Test
    void words_a_shell_would_split_are_single_quoted() {
        Field label = Field.of("label", "Label", FieldKind.TEXT).build();
        Form form = Form.of("f", "F", Step.of("s", "S", label));

        assertThat(FormCommand.render("jk x", form, Map.of("label", "it's mine"), Map.of(), DerivedDefaults.NONE))
                .isEqualTo("jk x -p 'label=it'\\''s mine'");
    }

    @Test
    void answers_that_do_not_settle_the_form_are_refused() {
        assertThatThrownBy(() -> FormCommand.render("jk new", FormTest.sample(), Map.of(), FLAGS, DerivedDefaults.NONE))
                .isInstanceOf(FormException.class)
                .hasMessage("answers do not settle the form: name needs a value");
    }
}
