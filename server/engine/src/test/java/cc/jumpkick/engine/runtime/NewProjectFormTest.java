// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.giter8.JkTemplateToml;
import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The new-project form: jk's step, then the template's, built from what the template declares. */
class NewProjectFormTest {

    @Test
    void before_a_template_is_chosen_the_form_is_jks_step_alone() {
        Form form = NewProjectForm.project();
        assertThat(form.fields()).extracting(Field::key).containsExactly("lang", "template", "name", "group");
        Field template = requireNonNull(form.field("template"));
        assertThat(template.kind()).isEqualTo(FieldKind.SEARCH);
        assertThat(template.sourceFor(Map.of("lang", "kotlin"))).isEqualTo("/api/templates?lang=kotlin");
        assertThat(requireNonNull(form.field("group")).required()).isFalse();
    }

    @Test
    void a_template_adds_its_layouts_and_its_asks_and_lends_the_form_its_language() throws Exception {
        Path app = RepoRoot.find(NewProjectFormTest.class).resolve("templates/kotlin/none/app.g8");
        NewProjectOps.TemplateForm template =
                NewProjectOps.templateForm(app.toString(), null, requireNonNull(app.getParent()));
        Form form = NewProjectForm.of(template);

        assertThat(form.steps()).extracting(s -> s.id()).containsExactly("project", JkTemplateToml.STEP_ID);
        assertThat(requireNonNull(form.field("lang")).defaultValue()).hasToString("Literal[value=kotlin]");
        assertThat(NewProjectForm.templateFields(form))
                .extracting(Field::key)
                .startsWith("layout")
                .contains("native", "assembly", "deps");

        FormCheck check = form.check(
                NewProjectForm.typed(form, Map.of("template", "app", "name", "demo", "native", "yes", "deps", "")),
                DerivedDefaults.NONE);
        assertThat(check.problems()).isEmpty();
        assertThat(check.values()).containsEntry("native", true).containsEntry("lang", "kotlin");
        assertThat(NewProjectForm.templateParams(check.values()))
                .containsEntry("native", "true")
                .doesNotContainKeys("name", "template", "lang", "layout");
    }

    @Test
    void a_single_layout_template_asks_no_layout(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("templates/java/none/one.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "one"
                layouts = ["traditional"]
                """);
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");

        NewProjectOps.TemplateForm template = NewProjectOps.templateForm(g8.toString(), null, temp);
        assertThat(template.layouts()).isEqualTo(List.of("traditional"));
        Form form = NewProjectForm.of(template);
        assertThat(form.field("layout")).isNull();
        assertThat(form.steps()).hasSize(1);
        assertThat(NewProjectForm.templateFields(form)).isEmpty();
    }
}
