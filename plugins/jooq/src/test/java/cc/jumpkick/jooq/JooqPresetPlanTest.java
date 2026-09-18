// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jooq;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.ProjectFacts;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code [jooq]} table as the generator entry it expands to. */
class JooqPresetPlanTest {

    private static final Path SHIM = Path.of("/shelf/jk-jooq.jar");
    private static final ProjectFacts PROJECT =
            new ProjectFacts("com.acme", "shop", "1.0", 25, null, false, false, Map.of());

    @Test
    void the_plugin_names_itself_and_its_prefix() {
        var manifest = new JooqPreset().manifest();
        assertThat(manifest.id()).isEqualTo("jk-jooq");
        assertThat(manifest.protocolPrefix()).isEqualTo("##JKJOOQ:");
    }

    /** An empty table: the Flyway migrations through DDLDatabase into <group>.jooq. */
    @Test
    void the_defaults_generate_from_the_migrations_into_the_groups_package() {
        GeneratorEntry entry = JooqPreset.entry(new PluginConfig("jooq", Map.of()), PROJECT, List.of(SHIM));

        assertThat(entry.name()).isEqualTo("jooq");
        assertThat(entry.stepName()).isEqualTo("generate-jooq");
        assertThat(entry.toolArtifact()).isEqualTo("jooq-codegen");
        assertThat(entry.toolCoordinate()).isEqualTo("org.jooq:jooq-codegen");
        assertThat(entry.main()).isEqualTo("cc.jumpkick.jooq.JooqMain");
        assertThat(entry.inputs()).containsExactly("src/main/resources/db/migration/**/*.sql");
        assertThat(entry.args())
                .containsExactly(
                        "--out",
                        "${out}",
                        "--package",
                        "com.acme.jooq",
                        "--schema",
                        "PUBLIC",
                        "--name-case",
                        "as_is",
                        "--includes",
                        ".*",
                        "${inputs}");
        assertThat(entry.contributes()).isEqualTo(GeneratorEntry.Contribution.SOURCES);
        assertThat(entry.out()).isEqualTo("generated/jooq");
        assertThat(entry.classpath()).containsExactly(SHIM);
    }

    @Test
    void every_key_reaches_the_main_and_a_live_database_is_the_jdbc_keys() {
        GeneratorEntry entry = JooqPreset.entry(
                new PluginConfig(
                        "jooq",
                        Map.ofEntries(
                                Map.entry("sql", "db/*.sql"),
                                Map.entry("package", "com.acme.db"),
                                Map.entry("schema", "shop"),
                                Map.entry("name-case", "lower"),
                                Map.entry("includes", "orders|customers"),
                                Map.entry("excludes", "flyway_schema_history"),
                                Map.entry("records", false),
                                Map.entry("pojos", true),
                                Map.entry("daos", true),
                                Map.entry("fluent-setters", true),
                                Map.entry("properties", Map.of("parseIgnoreComments", "true")),
                                Map.entry("jdbc-url", "jdbc:postgresql://localhost:5432/shop"),
                                Map.entry("jdbc-user", "shop"),
                                Map.entry("jdbc-password", "secret"))),
                PROJECT,
                List.of(SHIM));

        assertThat(entry.inputs()).containsExactly("db/*.sql");
        assertThat(entry.args())
                .containsExactly(
                        "--out",
                        "${out}",
                        "--package",
                        "com.acme.db",
                        "--schema",
                        "shop",
                        "--name-case",
                        "lower",
                        "--includes",
                        "orders|customers",
                        "--excludes",
                        "flyway_schema_history",
                        "--no-records",
                        "--pojos",
                        "--daos",
                        "--fluent-setters",
                        "--property",
                        "parseIgnoreComments=true",
                        "--jdbc-url",
                        "jdbc:postgresql://localhost:5432/shop",
                        "--jdbc-user",
                        "shop",
                        "--jdbc-password",
                        "secret",
                        "${inputs}");
    }

    @Test
    void the_worker_names_its_own_code_source() {
        assertThat(JooqPreset.ownJar()).singleElement().satisfies(p -> assertThat(p)
                .exists());
    }
}
