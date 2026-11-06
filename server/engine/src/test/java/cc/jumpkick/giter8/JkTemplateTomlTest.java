// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldDefault;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkTemplateTomlTest {

    private static final String HEAD = """
            language = "java"
            framework = "spring-boot"
            name = "webmvc"
            description = "Clean-architecture WebMVC workspace"
            layouts = ["traditional", "simple"]
            """;

    @Test
    void asks_parse_into_form_fields_in_order() {
        JkTemplateToml.Meta meta = JkTemplateToml.parse(HEAD + """
                internal = ["boot_version"]

                [[ask]]
                key = "database"
                label = "Database"
                help = "Where the app keeps its data"
                kind = "choice"
                options = ["none", { id = "postgres", label = "PostgreSQL" }, "mysql"]
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
                options = ["actuator", "devtools"]
                default = ["actuator"]
                advanced = true

                [[ask]]
                key = "native"
                label = "Build a native binary"
                kind = "bool"
                default = false
                """, "t.toml");

        assertThat(meta.internal()).containsExactly("boot_version");
        assertThat(meta.asks()).extracting(Field::key).containsExactly("database", "migrations", "extras", "native");
        Field database = meta.asks().getFirst();
        assertThat(database.kind()).isEqualTo(FieldKind.CHOICE);
        assertThat(database.help()).isEqualTo("Where the app keeps its data");
        assertThat(database.options()).extracting(o -> o.display()).containsExactly("none", "PostgreSQL", "mysql");
        assertThat(database.defaultValue()).isEqualTo(new FieldDefault.Literal("postgres"));
        assertThat(database.required()).isFalse();
        assertThat(meta.asks().get(1).when()).isNotNull();
        assertThat(meta.asks().get(2).advanced()).isTrue();
        assertThat(meta.asks().get(2).defaultValue()).isEqualTo(new FieldDefault.Literal(List.of("actuator")));
        assertThat(meta.asks().get(3).defaultValue()).isEqualTo(new FieldDefault.Literal(false));
        assertThat(meta.step()).get().extracting(s -> s.fields().size()).isEqualTo(4);
    }

    @Test
    void a_template_without_asks_has_no_step() {
        assertThat(JkTemplateToml.parse(HEAD, "t.toml").step()).isEmpty();
    }

    @Test
    void lint_refuses_an_undeclared_property_naming_it() {
        JkTemplateToml.Meta meta = JkTemplateToml.parse(HEAD, "t.toml");
        assertThatThrownBy(() -> JkTemplateToml.lint(meta, Map.of("name", "x", "db_flavour", "pg"), "t.toml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("t.toml")
                .hasMessageContaining("'db_flavour'")
                .hasMessageContaining("internal");
    }

    @Test
    void lint_passes_reserved_asked_and_internal_properties() {
        JkTemplateToml.Meta meta = JkTemplateToml.parse(HEAD + """
                internal = ["boot_version"]

                [[ask]]
                key = "database"
                label = "Database"
                kind = "choice"
                options = ["none", "postgres"]
                default = "none"
                """, "t.toml");
        JkTemplateToml.lint(
                meta,
                Map.of(
                        "name", "x",
                        "organization", "com.x",
                        "package", "com.x",
                        "java", "25",
                        "jk_languages", "java",
                        "jk_layout", "simple",
                        "boot_version", "maven(org.springframework.boot, spring-boot, stable)",
                        "database", "none"),
                "t.toml");
    }

    @Test
    void lint_refuses_an_ask_for_a_reserved_key() {
        assertThatThrownBy(() -> lint("""
                        [[ask]]
                        key = "package"
                        label = "Package"
                        kind = "text"
                        default = "com.x"
                        """)).hasMessageContaining("'package' is reserved");
    }

    @Test
    void lint_refuses_internal_that_is_not_a_property_or_is_reserved_or_asked() {
        assertThatThrownBy(() -> lint("internal = [\"ghost\"]\n")).hasMessageContaining("'ghost'");
        assertThatThrownBy(() -> lint("internal = [\"name\"]\n")).hasMessageContaining("'name', which is reserved");
    }

    @Test
    void lint_refuses_a_when_naming_a_later_or_unknown_key() {
        assertThatThrownBy(() -> lint("""
                        [[ask]]
                        key = "migrations"
                        label = "Migrations"
                        kind = "choice"
                        options = ["flyway", "liquibase"]
                        default = "flyway"
                        when = "database != 'none'"

                        [[ask]]
                        key = "database"
                        label = "Database"
                        kind = "choice"
                        options = ["none", "postgres"]
                        default = "none"
                        """)).hasMessageContaining("migrations").hasMessageContaining("comes after it");
        assertThatThrownBy(() -> lint("""
                        [[ask]]
                        key = "migrations"
                        label = "Migrations"
                        kind = "bool"
                        default = false
                        when = "db == 'x'"
                        """)).hasMessageContaining("'db'");
    }

    @Test
    void lint_refuses_a_default_that_is_not_an_option() {
        assertThatThrownBy(() -> lint("""
                        [[ask]]
                        key = "database"
                        label = "Database"
                        kind = "choice"
                        options = ["none", "postgres"]
                        default = "oracle"
                        """))
                .hasMessageContaining("database")
                .hasMessageContaining("default does not fit");
    }

    @Test
    void lint_refuses_a_binding_that_shadows_a_property() {
        assertThatThrownBy(() ->
                        JkTemplateToml.lint(JkTemplateToml.parse(HEAD + """
                                internal = ["db_pg"]

                                [[ask]]
                                key = "db"
                                label = "Database"
                                kind = "choice"
                                options = ["none", "pg"]
                                default = "none"
                                """, "t.toml"), Map.of("db_pg", "x"), "t.toml"))
                .hasMessageContaining("binds 'db_pg'");
    }

    @Test
    void lint_refuses_a_default_properties_value_that_disagrees_with_the_ask() {
        JkTemplateToml.Meta meta = JkTemplateToml.parse(HEAD + """
                [[ask]]
                key = "database"
                label = "Database"
                kind = "choice"
                options = ["none", "postgres"]
                default = "none"
                """, "t.toml");
        assertThatThrownBy(() -> JkTemplateToml.lint(meta, Map.of("database", "postgres"), "t.toml"))
                .hasMessageContaining("'database' defaults to 'none'");
    }

    @Test
    void parse_refuses_unknown_attributes_and_kinds_a_template_cannot_bind() {
        assertThatThrownBy(() -> lint("""
                        [[ask]]
                        key = "database"
                        label = "Database"
                        kind = "choice"
                        option = ["none"]
                        """)).hasMessageContaining("unknown attribute 'option'");
        assertThatThrownBy(() -> lint("""
                        [[ask]]
                        key = "where"
                        label = "Where"
                        kind = "path"
                        """)).hasMessageContaining("not path");
        assertThatThrownBy(() -> JkTemplateToml.parse(HEAD + "asks = []\n", "t.toml"))
                .hasMessageContaining("unknown key 'asks'");
    }

    @Test
    void load_reads_default_properties_and_refuses_a_failing_template(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("webmvc.g8");
        Files.createDirectories(g8);
        Files.writeString(g8.resolve(JkTemplateToml.FILE_NAME), HEAD);
        Files.writeString(g8.resolve("default.properties"), "name=x\nflavour=y\n");
        assertThatThrownBy(() -> JkTemplateToml.load(g8)).hasMessageContaining("'flavour'");
        assertThat(JkTemplateToml.loadOrRefuse(g8)).isEmpty();

        Files.writeString(g8.resolve("default.properties"), "name=x\n");
        assertThat(JkTemplateToml.load(g8))
                .get()
                .extracting(JkTemplateToml.Meta::name)
                .isEqualTo("webmvc");
        assertThat(JkTemplateToml.load(temp)).isEmpty();
    }

    /**
     * The catalogue lint over every template this checkout ships: the official catalogue's tree
     * ({@code templates/}, which the public catalogue mirrors) and every plugin's bundled templates.
     */
    @Test
    void every_shipped_template_passes_lint() throws Exception {
        Path repo = RepoRoot.find(JkTemplateTomlTest.class);
        List<Path> roots = new ArrayList<>();
        roots.add(repo.resolve("templates"));
        try (Stream<Path> plugins = Files.list(repo.resolve("plugins"))) {
            plugins.map(p -> p.resolve("src/main/resources/templates"))
                    .filter(Files::isDirectory)
                    .forEach(roots::add);
        }
        List<Path> templates = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.find(
                    root,
                    3,
                    (p, a) -> a.isDirectory() && p.getFileName().toString().endsWith(".g8"))) {
                walk.sorted().forEach(templates::add);
            }
        }
        assertThat(templates).as("templates under %s", roots).hasSizeGreaterThanOrEqualTo(20);
        for (Path template : templates) {
            assertThat(JkTemplateToml.load(template))
                    .as("%s has a %s", template, JkTemplateToml.FILE_NAME)
                    .isPresent();
        }
    }

    private static void lint(String tail) {
        JkTemplateToml.lint(JkTemplateToml.parse(HEAD + tail, "t.toml"), Map.of(), "t.toml");
    }
}
