// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewProjectOpsTest {

    @Test
    void no_template_is_the_app_template(@TempDir Path temp) throws Exception {
        // temp is under java.io.tmpdir → allowed parent
        var result = NewProjectOps.create(
                new NewProjectOps.Request("widget", temp.toString(), "com.acme", "java", "simple", null));
        Path root = result.path();
        assertThat(root).isEqualTo(temp.resolve("widget"));
        assertThat(root.resolve("jk.toml")).exists();
        assertThat(root.resolve("AGENTS.md")).exists();
        assertThat(Files.readString(root.resolve("AGENTS.md"))).contains("jk skill");
        String toml = Files.readString(root.resolve("jk.toml"));
        assertThat(toml).contains("name = \"widget\"").contains("group = \"com.acme\"");
        assertThat(toml).contains("main = \"com.acme.Main\"");
        assertThat(root.resolve("src/com/acme/Main.java")).isRegularFile();
    }

    @Test
    void rejects_existing_project(@TempDir Path temp) throws Exception {
        NewProjectOps.create(new NewProjectOps.Request("dup", temp.toString(), "com.example", "java", "simple", null));
        assertThatThrownBy(() -> NewProjectOps.create(
                        new NewProjectOps.Request("dup", temp.toString(), "com.example", "java", "simple", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejects_bad_name(@TempDir Path temp) {
        assertThatThrownBy(() -> NewProjectOps.create(
                        new NewProjectOps.Request("../evil", temp.toString(), "com.example", "java", "simple", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_name_is_lowercase_letters_digits_and_dashes(@TempDir Path temp) {
        for (String bad : new String[] {"Widget", "my.app", "my_app", "1app"}) {
            assertThatThrownBy(() -> NewProjectOps.create(
                            new NewProjectOps.Request(bad, temp.toString(), "com.example", "java", null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid name '" + bad + "'");
        }
    }

    @Test
    void rejects_unknown_layout(@TempDir Path temp) {
        assertThatThrownBy(() -> NewProjectOps.create(
                        new NewProjectOps.Request("widget", temp.toString(), "com.example", "java", "mill", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layout must be");
    }

    @Test
    void auto_layout_scaffolds_traditional_tree(@TempDir Path temp) throws Exception {
        var result = NewProjectOps.create(
                new NewProjectOps.Request("widget", temp.toString(), "com.acme", "java", "auto", null));
        Path root = result.path();
        assertThat(root.resolve("src/main/java")).isDirectory();
        assertThat(Files.readString(root.resolve("jk.toml"))).doesNotContain("layout");
    }

    @Test
    void target_dir_scaffolds_into_the_requested_directory(@TempDir Path temp) throws Exception {
        // targetDir wins over parentDir/name for the write location.
        Path custom = temp.resolve("elsewhere/custom-home");
        var created = NewProjectOps.create(new NewProjectOps.Request(
                "x",
                temp.toString(),
                "com.example",
                "java",
                "simple",
                null,
                true,
                Map.of(),
                true,
                custom.toString(),
                null));
        assertThat(created.path()).isEqualTo(custom.toAbsolutePath().normalize());
        assertThat(custom.resolve("jk.toml")).exists();
        assertThat(temp.resolve("x")).doesNotExist();
    }

    @Test
    void target_dir_outside_the_allowlist_is_refused_without_relax(@TempDir Path temp) {
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "x",
                        temp.toString(),
                        "com.example",
                        "java",
                        "simple",
                        null,
                        true,
                        Map.of(),
                        false,
                        "/etc/pwned",
                        null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOME");
    }

    @Test
    void rejects_path_outside_home_and_tmp() {
        assertThatThrownBy(() -> NewProjectOps.create(
                        new NewProjectOps.Request("x", "/etc", "com.example", "java", "simple", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOME");
    }

    @Test
    void symlinked_parent_pointing_outside_the_allowlist_is_refused(@TempDir Path temp) throws Exception {
        // Java.io.tmpdir is world-writable, so another local user can plant a link there;
        // a lexical check would accept it and the scaffolder would write through it. The target
        // must be genuinely outside both roots — @TempDir usually lives under /tmp, so it is not.
        Path outside = Path.of("/etc");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(outside)
                        && !outside.startsWith(home)
                        && !outside.startsWith(tmpDir.toAbsolutePath().normalize()),
                "needs a directory outside $HOME and the temp dir");
        Path link = tmpDir.resolve("jk-test-escape-" + ProcessHandle.current().pid());
        Symlinks.create(link, outside);
        try {
            assertThatThrownBy(() -> NewProjectOps.assertAllowedParent(link))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be under");
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void a_real_directory_under_tmp_is_still_allowed(@TempDir Path temp) throws Exception {
        Path real = Files.createDirectories(Path.of(
                System.getProperty("java.io.tmpdir"),
                "jk-test-ok-" + ProcessHandle.current().pid()));
        try {
            NewProjectOps.assertAllowedParent(real); // must not throw
        } finally {
            Files.deleteIfExists(real);
        }
    }

    @Test
    void template_apply_seeds_agents_md_when_missing(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("seed-agents.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);
        var result = NewProjectOps.create(new NewProjectOps.Request(
                "widget",
                parent.toString(),
                "com.acme",
                "java",
                "traditional",
                g8.toAbsolutePath().toString()));
        assertThat(result.path().resolve("jk.toml")).exists();
        assertThat(result.path().resolve("AGENTS.md")).exists();
        assertThat(Files.readString(result.path().resolve("AGENTS.md"))).contains("jk skill");
    }

    @Test
    void failed_template_apply_cleans_the_target_so_retry_works(@TempDir Path temp) throws Exception {
        Path g8 = temp.resolve("no-jktoml.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/README.md"), "# $name$\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);
        var request = new NewProjectOps.Request(
                "widget",
                parent.toString(),
                "com.acme",
                "java",
                "traditional",
                g8.toAbsolutePath().toString());

        assertThatThrownBy(() -> NewProjectOps.create(request)).hasMessageContaining("did not produce jk.toml");
        assertThat(parent.resolve("widget"))
                .as("failed scaffold must not leave a half-written target")
                .doesNotExist();

        // Fixing the template makes the same request succeed — no manual rm -rf in between.
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
        assertThat(NewProjectOps.create(request).path().resolve("jk.toml")).exists();
    }

    @Test
    void layout_simple_reaches_path_templates(@TempDir Path temp) throws Exception {
        // Path refs have no indexed metadata (spec empty) — the flag must still set simple=yes.
        Path g8 = temp.resolve("dual.g8");
        String src = "src/main/g8/src/$if(simple.truthy)$.$else$main$endif$/$if(simple.truthy)$.$else$java$endif$";
        Files.createDirectories(g8.resolve(src));
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
        Files.writeString(g8.resolve(src + "/Main.java"), "class Main {}\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);

        var result = NewProjectOps.create(new NewProjectOps.Request(
                "widget",
                parent.toString(),
                "com.acme",
                "java",
                "simple",
                g8.toAbsolutePath().toString()));

        assertThat(result.path().resolve("src/Main.java")).exists();
        assertThat(result.path().resolve("src/main/java")).doesNotExist();
    }

    @Test
    void layout_simple_refused_when_template_metadata_omits_it(@TempDir Path temp) throws Exception {
        Path templates = temp.resolve("templates");
        Path g8 = templates.resolve("java").resolve("none").resolve("acme-trad-only.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "acme-trad-only"
                description = "demo"
                layouts = ["traditional"]
                """);
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);

        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "widget", parent.toString(), "com.acme", "java", "simple", "acme-trad-only")))
                .hasMessageContaining("does not support --layout simple")
                .hasMessageContaining("traditional");
        assertThat(parent.resolve("widget")).doesNotExist();
    }

    @Test
    void resolve_template_finds_dogfood_short_name(@TempDir Path temp) throws Exception {
        // Unique short name so the official cache cannot steal the hit.
        Path templates = temp.resolve("templates");
        Path g8 = templates.resolve("java").resolve("none").resolve("acme-demo.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "acme-demo"
                description = "demo"
                layouts = ["traditional"]
                """);
        Files.writeString(g8.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name=demo\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);
        Path resolved = NewProjectOps.resolveTemplate("acme-demo", parent);
        assertThat(resolved).isEqualTo(g8.toAbsolutePath().normalize());
    }

    @Test
    void a_relative_template_path_resolves_against_the_request_s_cwd_for_the_form_and_the_scaffold(@TempDir Path temp)
            throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        javaTemplate(work.resolve("mine.g8"));
        Path parent = Files.createDirectories(temp.resolve("apps"));

        NewProjectOps.TemplateForm form = NewProjectOps.templateForm("./mine.g8", null, work);
        assertThat(form.id()).isEqualTo(work.resolve("mine.g8").toString());

        Path root = NewProjectOps.create(request(parent, "widget", "mine.g8", true, Map.of(), work.toString()))
                .path();
        assertThat(root.resolve("jk.toml")).content().contains("name = \"widget\"");

        // The project's parent is not the base: there the same ref names nothing.
        assertThatThrownBy(() -> NewProjectOps.create(request(parent, "other", "./mine.g8", true, Map.of(), null)))
                .hasMessageContaining("unknown template ref: ./mine.g8");
        // Without a cwd the base is parentDir.
        javaTemplate(parent.resolve("theirs.g8"));
        assertThat(NewProjectOps.create(request(parent, "third", "theirs.g8", true, Map.of(), null))
                        .path()
                        .resolve("jk.toml"))
                .exists();
    }

    @Test
    void a_template_directory_under_cwd_wins_over_the_catalogue_name_it_shares(@TempDir Path temp) throws Exception {
        Path work = Files.createDirectories(temp.resolve("work"));
        javaTemplate(work.resolve("app"));
        Files.createDirectories(work.resolve("library/src"));

        assertThat(NewProjectOps.templateForm("app", null, work).id())
                .isEqualTo(work.resolve("app").toString());
        // A directory without a template in it leaves the name to the catalogue.
        assertThat(NewProjectOps.templateForm("library", null, work).id()).isEqualTo("java/none/library");
    }

    @Test
    void a_directory_counts_as_a_template_when_it_holds_a_descriptor_properties_or_a_giter8_tree(@TempDir Path cwd)
            throws IOException {
        Files.createDirectories(cwd.resolve("g8/src/main/g8"));
        Files.createDirectories(cwd.resolve("described"));
        Files.writeString(cwd.resolve("described/.jk-template.toml"), "name = \"described\"\n");
        Files.createDirectories(cwd.resolve("props"));
        Files.writeString(cwd.resolve("props/default.properties"), "name=props\n");
        Files.createDirectories(cwd.resolve("app/src"));

        assertThat(NewProjectOps.isTemplateRoot(cwd.resolve("described"))).isTrue();
        assertThat(NewProjectOps.localTemplate("./g8", cwd)).contains(cwd.resolve("g8"));
        assertThat(NewProjectOps.localTemplate("described", cwd)).contains(cwd.resolve("described"));
        assertThat(NewProjectOps.localTemplate("props", cwd)).contains(cwd.resolve("props"));
        assertThat(NewProjectOps.localTemplate(cwd.resolve("props").toString(), Path.of("/")))
                .contains(cwd.resolve("props"));
        // Not a template, or not there: the catalogue, a remote or an error decides.
        assertThat(NewProjectOps.localTemplate("app", cwd)).isEmpty();
        assertThat(NewProjectOps.localTemplate("spring-boot/hello", cwd)).isEmpty();
        assertThat(NewProjectOps.localTemplate("https://github.com/acme/t.g8", cwd))
                .isEmpty();
    }

    @Test
    void a_standalone_project_targets_the_default_jdk_s_major_unless_java_is_answered(@TempDir Path temp)
            throws Exception {
        Path g8 = javaTemplate(temp.resolve("leveled.g8"));
        Path parent = Files.createDirectories(temp.resolve("apps"));
        JdkInventory inventory = new JdkInventory(temp.resolve("jdks"), temp.resolve("jk-jdks.toml"));
        inventory.setDefault(new InstalledJdk("temurin-21.0.7", temp.resolve("jdks/temurin-21.0.7")));
        NewProjectJava.inventory(() -> inventory);
        try {
            Path standalone = NewProjectOps.create(request(parent, "solo", g8.toString(), true, Map.of(), null))
                    .path();
            assertThat(standalone.resolve("jk.toml")).content().contains("java = 21");

            Path answered = NewProjectOps.create(
                            request(parent, "pinned", g8.toString(), true, Map.of("java", "17"), null))
                    .path();
            assertThat(answered.resolve("jk.toml")).content().contains("java = 17");

            // A module keeps what its caller sent, else the template's own.
            Path module = NewProjectOps.create(request(parent, "member", g8.toString(), false, Map.of(), null))
                    .path();
            assertThat(module.resolve("jk.toml")).content().contains("java = 25");
        } finally {
            NewProjectJava.inventory(null);
        }

        NewProjectJava.inventory(() -> new JdkInventory(temp.resolve("none"), temp.resolve("none.toml")));
        try {
            Path plain = NewProjectOps.create(request(parent, "plain", g8.toString(), true, Map.of(), null))
                    .path();
            assertThat(plain.resolve("jk.toml")).content().contains("java = 25");
        } finally {
            NewProjectJava.inventory(null);
        }
    }

    /** A Giter8 template at {@code g8} whose {@code jk.toml} carries its name and {@code java} (25 by default). */
    private static Path javaTemplate(Path g8) throws IOException {
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=demo\njava=25\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), """
                name = "$name$"
                java = $java$
                """);
        return g8;
    }

    private static NewProjectOps.Request request(
            Path parent,
            String name,
            String template,
            boolean standalone,
            Map<String, String> params,
            @Nullable String cwd) {
        return new NewProjectOps.Request(
                name, parent.toString(), "com.acme", null, null, template, standalone, params, false, null, cwd);
    }

    @Test
    void the_shipped_cli_template_scaffolds_its_simple_layout_with_no_layout_given(@TempDir Path temp)
            throws Exception {
        Path cli = RepoRoot.find(NewProjectOpsTest.class).resolve("templates/java/none/cli.g8");
        assertThat(cli.resolve("default.properties")).content().contains("jk_layout=simple");
        Path parent = Files.createDirectories(temp.resolve("apps"));

        Path root = NewProjectOps.create(
                        new NewProjectOps.Request("widget", parent.toString(), "com.acme", null, null, cli.toString()))
                .path();

        assertThat(root.resolve("src/main")).doesNotExist();
        try (var files = Files.walk(root.resolve("src"))) {
            assertThat(files.filter(f -> f.getFileName().toString().equals("Main.java")))
                    .singleElement()
                    .satisfies(
                            main -> assertThat(root.relativize(main).toString()).doesNotContain("java/"));
        }
    }

    @Test
    void a_template_default_of_simple_renders_the_simple_tree(@TempDir Path temp) throws Exception {
        assertThat(scaffoldDual(temp, "simple", null, Map.of())).isEqualTo("src/Main.java");
    }

    @Test
    void auto_leaves_the_layout_to_the_template(@TempDir Path temp) throws Exception {
        assertThat(scaffoldDual(temp, "simple", "auto", Map.of())).isEqualTo("src/Main.java");
    }

    @Test
    void a_template_without_a_default_renders_the_traditional_tree(@TempDir Path temp) throws Exception {
        assertThat(scaffoldDual(temp, null, null, Map.of())).isEqualTo("src/main/java/Main.java");
    }

    @Test
    void an_explicit_layout_overrides_the_template_default(@TempDir Path temp) throws Exception {
        assertThat(scaffoldDual(temp, "simple", "traditional", Map.of())).isEqualTo("src/main/java/Main.java");
    }

    @Test
    void a_jk_layout_parameter_overrides_the_template_default(@TempDir Path temp) throws Exception {
        assertThat(scaffoldDual(temp, "simple", null, Map.of("jk_layout", "traditional")))
                .isEqualTo("src/main/java/Main.java");
    }

    @Test
    void an_explicit_layout_overrides_a_jk_layout_parameter(@TempDir Path temp) throws Exception {
        assertThat(scaffoldDual(temp, "traditional", "simple", Map.of("jk_layout", "traditional")))
                .isEqualTo("src/Main.java");
    }

    @Test
    void an_unknown_jk_layout_parameter_is_refused(@TempDir Path temp) {
        assertThatThrownBy(() -> scaffoldDual(temp, null, null, Map.of("jk_layout", "sideways")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_template_that_fails_lint_is_refused_naming_the_property(@TempDir Path temp) throws Exception {
        Path g8 = askingTemplate(temp, "name=demo\nflavour=sweet\n");
        Path parent = Files.createDirectories(temp.resolve("apps"));

        assertThatThrownBy(() -> create(parent, "widget", g8, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'flavour'")
                .hasMessageContaining("internal");
        assertThat(parent.resolve("widget")).doesNotExist();
    }

    @Test
    void template_answers_reach_the_giter8_bindings(@TempDir Path temp) throws Exception {
        Path g8 = askingTemplate(temp, "name=demo\n");
        Path parent = Files.createDirectories(temp.resolve("apps"));

        Path defaults = create(parent, "widget", g8, Map.of());
        assertThat(defaults.resolve("jk.toml")).content().isEqualTo("name = \"widget\"\ndb = \"none\"\n");

        Path answered = create(parent, "widget2", g8, Map.of("database", "postgres", "native", "yes"));
        assertThat(answered.resolve("jk.toml"))
                .content()
                .isEqualTo("name = \"widget2\"\ndb = \"postgres\"\npostgresql = \"42\"\n[native]\n");

        assertThatThrownBy(() -> create(parent, "widget3", g8, Map.of("database", "oracle")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database must be one of none, postgres")
                .hasMessageContaining("-p database=none|postgres");
    }

    /** A catalogue-shaped template with two asks whose jk.toml shows the bindings it got. */
    private static Path askingTemplate(Path temp, String defaultProperties) throws IOException {
        Path g8 = temp.resolve("templates/java/none/asking.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "asking"
                layouts = ["traditional"]

                [[ask]]
                key = "database"
                label = "Database"
                kind = "choice"
                options = ["none", "postgres"]
                default = "none"

                [[ask]]
                key = "native"
                label = "Native binary"
                kind = "bool"
                default = false
                """);
        Files.writeString(g8.resolve("default.properties"), defaultProperties);
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), """
                name = "$name$"
                db = "$database$"
                $if(database_postgres.truthy)$
                postgresql = "42"
                $endif$
                $if(native.truthy)$
                [native]
                $endif$
                """);
        return g8;
    }

    private static Path create(Path parent, String name, Path template, Map<String, String> params) throws IOException {
        return NewProjectOps.create(new NewProjectOps.Request(
                        name,
                        parent.toString(),
                        "com.acme",
                        "java",
                        "traditional",
                        template.toAbsolutePath().toString(),
                        false,
                        params,
                        false,
                        null,
                        null))
                .path();
    }

    /**
     * Scaffolds a template that renders either layout, with {@code templateDefault} as its
     * {@code jk_layout} default; returns where its {@code Main.java} landed.
     */
    private static String scaffoldDual(
            Path temp, @Nullable String templateDefault, @Nullable String layout, Map<String, String> params)
            throws Exception {
        Path g8 = temp.resolve("dual.g8");
        String src = "src/main/g8/src/$if(simple.truthy)$.$else$main$endif$/$if(simple.truthy)$.$else$java$endif$";
        Files.createDirectories(g8.resolve(src));
        Files.writeString(
                g8.resolve("default.properties"),
                "name=demo\n" + (templateDefault == null ? "" : "jk_layout=" + templateDefault + "\n"));
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name = \"$name$\"\n");
        Files.writeString(g8.resolve(src + "/Main.java"), "class Main {}\n");
        Path parent = Files.createDirectories(temp.resolve("apps"));
        Path root = NewProjectOps.create(new NewProjectOps.Request(
                        "widget",
                        parent.toString(),
                        "com.acme",
                        "java",
                        layout,
                        g8.toAbsolutePath().toString(),
                        false,
                        params,
                        false,
                        null,
                        null))
                .path();
        try (var files = Files.walk(root)) {
            return files.filter(f -> f.getFileName().toString().equals("Main.java"))
                    .map(f -> root.relativize(f).toString().replace('\\', '/'))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
