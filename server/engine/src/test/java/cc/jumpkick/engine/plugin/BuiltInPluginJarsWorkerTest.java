// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import org.junit.jupiter.api.Test;

/**
 * On a cold store the engine fetches the plugin that owns an unowned table before any manifest is
 * on disk, so the worker it asks for comes from the table name alone. A table is not always its
 * plugin's id: {@code [generate]} belongs to {@code generator}, whose worker is {@code
 * jk-generator}, and asking for {@code jk-generate} left the table unowned.
 */
class BuiltInPluginJarsWorkerTest {

    @Test
    void the_generate_table_fetches_the_generator_worker() {
        assertThat(BuiltInPluginJars.workerOwning("generate")).contains(PluginJar.GENERATOR);
    }

    @Test
    void a_plugin_id_names_its_worker_too() {
        assertThat(BuiltInPluginJars.workerOwning("generator")).contains(PluginJar.GENERATOR);
        assertThat(BuiltInPluginJars.workerOwning("openapi")).contains(PluginJar.OPENAPI);
    }

    @Test
    void every_built_in_table_names_a_worker_jk_ships() {
        assertThat(PluginTableRegistry.builtInTables()).isNotEmpty();
        PluginTableRegistry.builtInTables().forEach((id, table) -> assertThat(BuiltInPluginJars.workerOwning(table))
                .as("[%s] → jk-%s", table, id)
                .map(PluginJar::artifactId)
                .contains("jk-" + id));
    }

    @Test
    void a_table_no_built_in_owns_fetches_nothing() {
        assertThat(BuiltInPluginJars.workerOwning("not-a-plugin")).isEmpty();
    }
}
