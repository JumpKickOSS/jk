// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The flags of the former blank-project scaffolder: each either answers a field of the form or is
 * refused with the answer that replaced it.
 */
class NewFlagsTest {

    @Test
    void the_template_questions_the_blank_flags_answered_are_refused_with_their_p_answer() {
        NewCommand assembly = new NewCommand();
        assembly.assembly = true;
        assertThat(assembly.refusal()).isEqualTo("jk new: --assembly is gone; use -p assembly=true");

        NewCommand nativeImage = new NewCommand();
        nativeImage.nativeImage = true;
        assertThat(nativeImage.refusal()).isEqualTo("jk new: --native is gone; use -p native=true");

        NewCommand deps = new NewCommand();
        deps.depsCsv = "guava";
        assertThat(deps.refusal())
                .startsWith("jk new: --deps is gone; use -p deps=guava,commons-lang,commons-io")
                .contains("jk add <library>");
    }

    @Test
    void the_flags_no_template_offers_are_refused_with_what_to_do_instead() {
        NewCommand module = new NewCommand();
        module.kotlinModule = "core";
        assertThat(module.refusal())
                .isEqualTo("jk new: --kotlin-module is gone: set module = \"<name>\" in the new jk.toml");
    }

    @Test
    void jdk_answers_the_java_level_with_a_major_and_refuses_anything_else() {
        assertThat(NewCommand.javaLevel("21")).isEqualTo("21");
        assertThat(NewCommand.javaLevel("temurin-25")).isNull();
        assertThat(NewCommand.javaLevel("25.0.1")).isNull();
        assertThat(NewCommand.javaLevel("lts")).isNull();

        NewCommand vendor = new NewCommand();
        vendor.jdk = "temurin-25";
        assertThat(vendor.refusal())
                .isEqualTo(
                        "jk new: --jdk takes a Java major, as in --jdk 25; for a vendor set jdk = \"temurin-25\" in jk.toml");
        NewCommand major = new NewCommand();
        major.jdk = "21";
        assertThat(major.refusal()).isNull();
    }

    @Test
    void executable_names_the_template_unless_t_already_does() {
        NewCommand none = new NewCommand();
        assertThat(none.refusal()).isNull();
        assertThat(none.template()).as("no -t: the form's default, app").isNull();

        NewCommand app = new NewCommand();
        app.executable = true;
        assertThat(app.template()).isEqualTo("app");
        NewCommand library = new NewCommand();
        library.executable = false;
        assertThat(library.template()).isEqualTo("library");

        NewCommand both = new NewCommand();
        both.executable = true;
        both.templateRef = "cli";
        assertThat(both.refusal()).isEqualTo("jk new: -e and -t both pick a template; keep one");

        NewCommand init = new NewCommand();
        init.directory = Path.of(".");
        init.executable = false;
        init.templateRef = "cli";
        assertThat(init.refusal()).isEqualTo("jk init: --no-executable and -t both pick a template; keep one");
    }

    @Test
    void plugin_names_the_plugin_template_unless_another_flag_already_names_one() {
        NewCommand plugin = new NewCommand();
        plugin.plugin = true;
        assertThat(plugin.refusal()).isNull();
        assertThat(plugin.template()).isEqualTo("plugin");

        NewCommand withT = new NewCommand();
        withT.plugin = true;
        withT.templateRef = "cli";
        assertThat(withT.refusal()).isEqualTo("jk new: --plugin and -t both pick a template; keep one");

        NewCommand withE = new NewCommand();
        withE.plugin = true;
        withE.executable = true;
        assertThat(withE.refusal()).isEqualTo("jk new: -e and --plugin both pick a template; keep one");
    }
}
