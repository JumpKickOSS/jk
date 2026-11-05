// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SharedHomeTemplateCatalogTest {

    private static final String URL = "file:///slot/template-catalog/abc.git";

    @Test
    void an_empty_configuration_gains_the_catalog_block() {
        String config = SharedHomeTemplateCatalog.withCatalog("", URL);

        assertThat(config).contains("[templates]\nofficial = \"" + URL + "\"\n");
    }

    @Test
    void the_block_goes_after_the_tables_already_there_and_a_second_pass_changes_nothing() {
        String first = SharedHomeTemplateCatalog.withCatalog("[m2]\nintegration = false\n", URL);

        assertThat(first).startsWith("[m2]\nintegration = false\n\n" + SharedHomeTemplateCatalog.BEGIN);
        assertThat(SharedHomeTemplateCatalog.withCatalog(first, URL)).isEqualTo(first);
    }

    @Test
    void a_new_catalog_replaces_the_block_rather_than_adding_another() {
        String first = SharedHomeTemplateCatalog.withCatalog("[m2]\nintegration = false\n", URL);
        String next = SharedHomeTemplateCatalog.withCatalog(first, "file:///slot/template-catalog/def.git");

        assertThat(next).contains("def.git").doesNotContain("abc.git");
        assertThat(next.split("\\[templates\\]", -1)).hasSize(2);
    }

    @Test
    void a_templates_table_written_by_someone_else_is_left_alone() {
        String own = "[templates]\nofficial = \"https://git.corp/templates.git\"\n";

        assertThat(SharedHomeTemplateCatalog.withCatalog(own, URL)).isEqualTo(own);
    }
}
