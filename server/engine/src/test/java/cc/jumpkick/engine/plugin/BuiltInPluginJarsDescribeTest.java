// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.manifest.PluginDescriptor;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A shelved worker jar's root descriptor must be its own. The loader checks the worker the
 * descriptor names against the artifact the jar is shelved as and refuses a mismatch, so a
 * vendored sibling's descriptor can neither claim the jar nor take the sibling's table.
 */
class BuiltInPluginJarsDescribeTest {

    private static final Path JAR = Path.of("/store/repos/jk-local/cc/jumpkick/jk-grails/1.0/jk-grails-1.0.jar");

    private static String descriptor(String id, @Nullable String worker) {
        String code = worker == null ? "" : "[code]\nworker = \"" + worker + "\"\nprotocol-prefix = \"##X:\"\n";
        return "[plugin]\nid = \"" + id + "\"\ntable = \"" + id + "\"\njk-compat = \">=0.10\"\n\n" + code;
    }

    @Test
    void a_descriptor_naming_the_jar_s_own_worker_is_accepted() {
        var located = new BuiltInPluginJars.Located(PluginJar.GRAILS, JAR, descriptor("grails", "jk-grails"));

        PluginDescriptor d = BuiltInPluginJars.describe(located, false);

        assertThat(d.id()).isEqualTo("grails");
        assertThat(d.table()).isEqualTo("grails");
    }

    @Test
    void a_descriptor_of_another_plugin_at_the_jar_root_is_refused_naming_both() {
        var located = new BuiltInPluginJars.Located(PluginJar.GRAILS, JAR, descriptor("spring-boot", "jk-spring-boot"));

        assertThatThrownBy(() -> BuiltInPluginJars.describe(located, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jk-grails")
                .hasMessageContaining("spring-boot")
                .hasMessageContaining("not registered");
    }

    /**
     * A shelved worker that carries no descriptor for the table a project configures is a shelf
     * behind the plugin the project was written for: the detail names the jar, the checkout
     * install and the jar override — never the generic "add it under [plugins]" alone.
     */
    @Test
    void a_shelved_worker_that_does_not_own_the_table_names_the_jar_and_both_remedies() {
        Path jar = Path.of("/store/repos/jk-local/cc/jumpkick/jk-openapi/0.13.7/jk-openapi-0.13.7.jar");

        String detail = BuiltInPluginJars.doesNotOwn(PluginJar.OPENAPI, jar, "openapi");

        assertThat(detail)
                .contains("jk-openapi (" + jar + ") does not own [openapi]")
                .contains("newer jk-openapi")
                .contains("`jk install`")
                .contains("-Djk.openapi.plugin.jar=");
    }

    @Test
    void a_descriptor_without_a_code_table_is_matched_by_its_id() {
        var own = new BuiltInPluginJars.Located(PluginJar.GRAILS, JAR, descriptor("grails", null));
        var foreign = new BuiltInPluginJars.Located(PluginJar.GRAILS, JAR, descriptor("quarkus", null));

        assertThat(BuiltInPluginJars.describe(own, false).id()).isEqualTo("grails");
        assertThatThrownBy(() -> BuiltInPluginJars.describe(foreign, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jk-quarkus");
    }
}
