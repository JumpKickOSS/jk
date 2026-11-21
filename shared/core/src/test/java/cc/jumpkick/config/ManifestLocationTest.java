// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.SourceLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A manifest that does not parse or validate says where: the file, and the line and column it can name. */
class ManifestLocationTest {

    private static final String HEAD = """
            group = "x.y"
            name = "app"
            version = "0.1.0"
            """;

    private static final String WORKSPACE_OF_APP = """
            group = "x.y"
            name = "ws"
            version = "0.1.0"
            java = 25

            [workspace]
            modules = ["app"]
            """;

    private static final String ANDROID = """
            [android]
            namespace = "com.example.app"
            compile-sdk = 28
            min-sdk = 24
            """;

    @Test
    void a_syntax_error_is_located_where_toml_stopped(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = 25\n\n[dependencies]\nfoo = { group = \"a\" \n");
        SourceLocation at = locationOf(file);
        assertThat(at.file()).isEqualTo(file.toString());
        assertThat(at.line()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void an_unknown_key_in_a_dependency_entry_is_located_at_the_key(@TempDir Path dir) throws Exception {
        Path file = write(
                dir,
                HEAD + "java = 25\n\n[dependencies]\nnope = { group = \"g\", artifact = \"a\", version = \"1\" }\n");
        assertThat(locationOf(file)).isEqualTo(new SourceLocation(file.toString(), 7, 23));
    }

    @Test
    void an_unknown_key_in_a_generate_entry_is_refused_at_the_key(@TempDir Path dir) throws Exception {
        Path file = write(
                dir, HEAD + "java = 25\n\n[generate.api]\ntool = \"org.acme:gen:1.0\"\nsecret-evn = { A = \"b\" }\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessageContaining("[generate.api].secret-evn").hasMessageContaining("secret-env");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 8, 1));
    }

    @Test
    void an_unknown_key_in_a_plugin_table_is_refused_at_the_key(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = 25\n\n" + ANDROID + "  min-skd = 24\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessageContaining("[android].min-skd is not a [android] key; did you mean min-sdk?");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 10, 3));
    }

    @Test
    void an_unknown_plugin_key_close_to_none_lists_the_keys(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = 25\n\n" + ANDROID + "flavour-of-the-month = 1\n");
        assertThat(refusal(file))
                .hasMessageContaining("[android].flavour-of-the-month is not a [android] key (keys: namespace,");
    }

    @Test
    void a_plugin_value_of_the_wrong_type_is_located_at_its_key(@TempDir Path dir) throws Exception {
        Path file = write(
                dir,
                HEAD + "java = 25\n\n[android]\nnamespace = \"com.example.app\"\n"
                        + "compile-sdk = \"thirty-six\"\nmin-sdk = 24\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessageContaining("[android].compile-sdk must be an integer");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 8, 1));
    }

    @Test
    void a_plugin_group_entry_that_is_not_a_table_is_located_at_the_entry(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = 25\n\n" + ANDROID + "\n[android.signing]\nrelease = \"x\"\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessageContaining("[android.signing].release must be a table");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 12, 1));
    }

    @Test
    void an_unknown_key_in_a_variant_overlay_is_refused_at_the_key(@TempDir Path dir) throws Exception {
        Path file = write(
                dir,
                HEAD + "java = 25\n\n" + ANDROID
                        + "\n[variants.build-type.release.android]\napplication-id-sufix = \".rel\"\n");
        JkBuildParseException e = refusal(file);
        assertThat(e)
                .hasMessageContaining("[variants.build-type.release.android].application-id-sufix")
                .hasMessageContaining("did you mean application-id-suffix?");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 12, 1));
    }

    @Test
    void an_overlay_value_of_the_wrong_type_is_located_at_its_key(@TempDir Path dir) throws Exception {
        Path file = write(
                dir, HEAD + "java = 25\n\n" + ANDROID + "\n[variants.build-type.release.android]\nminify = \"yes\"\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessageContaining("[variants.build-type.release.android].minify must be a boolean");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 12, 1));
    }

    @Test
    void a_key_a_variant_cannot_set_is_located_at_the_key(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = 25\n\n[variants.flavor.demo]\nextra-scr = [\"src/demo\"]\n");
        JkBuildParseException e = refusal(file);
        assertThat(e)
                .hasMessageContaining("[variants.flavor.demo].extra-scr: a variant cannot set this")
                .hasMessageContaining("did you mean extra-src?");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 7, 1));
    }

    @Test
    void a_variant_value_that_is_not_a_table_is_located_at_its_name(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = 25\n\n[variants.flavor]\ndemo = 1\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessageContaining("[variants.flavor].demo must be a table");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 7, 1));
    }

    @Test
    void a_variant_default_naming_no_value_is_located_at_the_default(@TempDir Path dir) throws Exception {
        Path file = write(
                dir,
                HEAD + "java = 25\n\n[variants.flavor]\ndefault = \"paid\"\n\n"
                        + "[variants.flavor.demo]\nextra-src = [\"src/demo\"]\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessageContaining("default = \"paid\" names no declared value");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 7, 1));
    }

    @Test
    void a_group_defined_in_a_variant_overlay_is_refused_at_the_group(@TempDir Path dir) throws Exception {
        Path file = write(
                dir,
                HEAD + "java = 25\n\n" + ANDROID
                        + "\n[variants.build-type.release.android.signing]\nkey-alias = \"upload\"\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessageContaining("[variants.build-type.release.android.signing]: define");
        assertThat(e.location()).isNotNull();
        assertThat(Objects.requireNonNull(e.location()).line()).isEqualTo(11);
    }

    @Test
    void a_value_of_the_wrong_type_is_located_at_its_key(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = \"twenty-five\"\n");
        assertThat(locationOf(file)).isEqualTo(new SourceLocation(file.toString(), 4, 1));
    }

    @Test
    void a_malformed_range_in_a_dependency_entry_is_refused_at_its_version_key(@TempDir Path dir) throws Exception {
        String entry = "lib = { group = \"com.example\", version = \"(1.0)\" }";
        Path file = write(dir, HEAD + "java = 25\n\n[dependencies]\n" + entry + "\n");
        JkBuildParseException e = refusal(file);
        int column = entry.indexOf("version") + 1;
        assertThat(e)
                .hasMessage(file + ":7:" + column + ": dependencies.lib.version: malformed version range \"(1.0)\"");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 7, column));
    }

    @Test
    void a_malformed_range_in_a_shorthand_is_refused_at_its_key(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = 25\n\n[test-dependencies]\nlib = \"com.example:lib:[]\"\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessageContaining("malformed version range \"[]\"");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 7, 1));
    }

    @Test
    void a_range_whose_bounds_cross_is_refused_at_its_key(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "kotlin = \"[2.0,1.0]\"\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessage(file + ":4:1: kotlin: malformed version range \"[2.0,1.0]\"");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 4, 1));
    }

    @Test
    void a_malformed_range_in_workspace_dependencies_is_refused_at_its_key(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                group = "x.y"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = []

                [workspace.dependencies]
                lib = { group = "com.example", version = "[1.0,2.0" }
                """);
        JkBuildParseException e = refusal(file);
        assertThat(e)
                .hasMessageStartingWith(file + ":10:")
                .hasMessageEndingWith(": workspace.dependencies.lib.version: malformed version range \"[1.0,2.0\"");
        assertThat(e.location()).isNotNull();
        assertThat(Objects.requireNonNull(e.location()).line()).isEqualTo(10);
    }

    @Test
    void a_well_formed_range_parses(@TempDir Path dir) throws Exception {
        Path file = write(
                dir,
                HEAD + "java = 25\n\n[dependencies]\n"
                        + "a = { group = \"com.example\", version = \">=1.0,<2\" }\n"
                        + "b = \"com.example:b:[1.0,2.0)\"\n");
        assertThat(JkBuildParser.parse(file).dependencies().of(Scope.MAIN)).hasSize(2);
    }

    @Test
    void a_check_that_names_no_key_is_the_whole_file(@TempDir Path dir) throws Exception {
        Path file = write(dir, "group = \"x.y\"\nversion = \"0.1.0\"\n");
        assertThat(locationOf(file)).isEqualTo(SourceLocation.of(file));
    }

    @Test
    void the_message_names_the_file_line_and_column_it_is_located_at(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = 25\n\n[build]\nbogus = 1\n");
        assertThat(refusal(file).getMessage()).startsWith(file + ":7:1: [build] unknown key `bogus`");
    }

    @Test
    void a_syntax_error_message_names_where_toml_stopped(@TempDir Path dir) throws Exception {
        Path file = write(dir, HEAD + "java = = 25\n");
        assertThat(refusal(file).getMessage()).startsWith(file + ":4:8: failed to parse jk.toml: ");
    }

    @Test
    void a_refusal_of_the_whole_file_leads_with_the_file(@TempDir Path dir) throws Exception {
        Path file = write(dir, "group = \"x.y\"\nversion = \"0.1.0\"\n");
        JkBuildParseException e = refusal(file);
        assertThat(e).hasMessage(file + ": jk.toml is missing required key `name`");
        assertThat(e.refusal()).isEqualTo("jk.toml is missing required key `name`");
    }

    @Test
    void a_refusal_that_already_names_its_file_does_not_name_it_twice(@TempDir Path dir) {
        Path file = dir.resolve("jk.toml").toAbsolutePath().normalize();
        assertThat(refusal(file)).hasMessage("jk.toml not found: " + file);
    }

    @Test
    void a_file_under_the_working_directory_is_named_relative_to_it(@TempDir Path dir) throws Exception {
        Path member = Files.createDirectories(dir.resolve("app"));
        Path file = write(member, HEAD + "java = 25\n\n[build]\nbogus = 1\n");
        JkBuildParseException e = SessionContext.where(Session.defaults().withWorkingDir(dir), () -> refusal(file));
        assertThat(e.getMessage()).startsWith("app/jk.toml:7:1: [build] unknown key `bogus`");
        assertThat(e.refusal()).startsWith("[build] unknown key `bogus`");
        assertThat(e.location()).isEqualTo(new SourceLocation(file.toString(), 7, 1));
    }

    @Test
    void a_whole_file_refusal_under_the_working_directory_leads_with_the_file_however_it_reads(@TempDir Path dir)
            throws Exception {
        Path file = write(dir, "group = \"x.y\"\nversion = \"0.1.0\"\n");
        JkBuildParseException e = SessionContext.where(Session.defaults().withWorkingDir(dir), () -> refusal(file));
        assertThat(e).hasMessage("jk.toml: jk.toml is missing required key `name`");
    }

    @Test
    void a_refused_member_is_named_from_the_member_jk_ran_in_not_from_the_root_the_request_names(@TempDir Path dir)
            throws Exception {
        Path root = write(dir, WORKSPACE_OF_APP);
        Path member = Files.createDirectories(dir.resolve("app"));
        Path memberFile = write(member, HEAD + "java = 25\n\n[build]\nbogus = 1\n");

        JkBuildParseException e = SessionContext.where(
                Session.defaults().withWorkingDir(dir),
                () -> Request.detached().withCallerDir(member).call(() -> memberRefusal(root)));

        assertThat(e.getMessage()).startsWith("jk.toml:7:1: [build] unknown key `bogus`");
        assertThat(e.location()).isEqualTo(new SourceLocation(memberFile.toString(), 7, 1));
    }

    @Test
    void a_refused_member_is_named_from_the_root_jk_ran_in_not_from_the_member_the_request_names(@TempDir Path dir)
            throws Exception {
        Path member = Files.createDirectories(dir.resolve("app"));
        Path memberFile = write(member, HEAD + "java = 25\n\n[build]\nbogus = 1\n");

        JkBuildParseException e = SessionContext.where(
                Session.defaults().withWorkingDir(member),
                () -> Request.detached().withCallerDir(dir).call(() -> refusal(memberFile)));

        assertThat(e.getMessage()).startsWith("app/jk.toml:7:1: [build] unknown key `bogus`");
    }

    @Test
    void a_member_outside_the_directory_jk_ran_in_is_named_in_full(@TempDir Path dir) throws Exception {
        Path member = Files.createDirectories(dir.resolve("app"));
        Path elsewhere = Files.createDirectories(dir.resolve("lib"));
        Path memberFile = write(member, HEAD + "java = 25\n\n[build]\nbogus = 1\n");

        JkBuildParseException e = SessionContext.where(
                Session.defaults().withWorkingDir(dir),
                () -> Request.detached().withCallerDir(elsewhere).call(() -> refusal(memberFile)));

        assertThat(e.getMessage()).startsWith(memberFile + ":7:1: ");
    }

    @Test
    void a_member_refused_as_a_whole_is_named_by_its_own_file(@TempDir Path dir) throws Exception {
        Path root = write(dir, """
                group = "x.y"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["lib"]
                """);
        Path memberFile = write(Files.createDirectories(dir.resolve("lib")), "version = \"0.1.0\"\n");

        JkBuildParseException e = memberRefusal(root);

        assertThat(e).hasMessage(memberFile + ": jk.toml is missing required key `name`");
        assertThat(e.location()).isEqualTo(SourceLocation.of(memberFile));
    }

    @Test
    void a_member_refused_after_its_parse_is_located_in_its_own_file(@TempDir Path dir) throws Exception {
        Path root = write(dir, """
                group = "x.y"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["web"]
                """);
        Path memberFile = write(
                Files.createDirectories(dir.resolve("web")), HEAD + "\n[node]\nversion = 24\nframework = \"vite\"\n");

        JkBuildParseException e = memberRefusal(root);

        assertThat(e).hasMessageStartingWith(memberFile + ": [node] is set but ./package.json is missing");
        assertThat(e.location()).isEqualTo(SourceLocation.of(memberFile));
    }

    @Test
    void a_member_list_the_root_cannot_load_is_refused_in_the_root(@TempDir Path dir) throws Exception {
        Path root = write(dir, """
                group = "x.y"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["app", "lib"]
                """);
        write(Files.createDirectories(dir.resolve("app")), HEAD + "java = 25\n");
        Files.createDirectories(dir.resolve("lib"));

        JkBuildParseException e = memberRefusal(root);

        assertThat(e.getMessage()).startsWith(root + ": ").contains("lib");
        assertThat(e.location()).isEqualTo(SourceLocation.of(root));
    }

    @Test
    void a_dependency_is_located_at_its_key_in_the_member_that_declares_it(@TempDir Path dir) throws Exception {
        write(dir, """
                group = "x.y"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["app"]
                """);
        Path member = Files.createDirectories(dir.resolve("app"));
        Path memberFile = write(
                member, HEAD + "java = 25\n\n[dependencies]\nlib = { group = \"com.example\", version = \"1.0\" }\n");

        assertThat(ManifestLocator.dependency(dir, List.of("absent:thing", "com.example:lib")))
                .isEqualTo(new SourceLocation(memberFile.toString(), 7, 1));
        assertThat(ManifestLocator.dependency(dir, List.of("absent:thing"))).isNull();
    }

    private static SourceLocation locationOf(Path file) {
        JkBuildParseException e = refusal(file);
        SourceLocation at = e.location();
        assertThat(at).as("the refusal is located").isNotNull();
        return Objects.requireNonNull(at);
    }

    private static JkBuildParseException refusal(Path file) {
        JkBuildParseException e = catchThrowableOfType(JkBuildParseException.class, () -> JkBuildParser.parse(file));
        assertThat(e).as("the manifest is refused").isNotNull();
        return e;
    }

    private static JkBuildParseException memberRefusal(Path root) throws Exception {
        JkBuild parsed = JkBuildParser.parseLocal(root);
        Path dir = Objects.requireNonNull(root.getParent());
        JkBuildParseException e =
                catchThrowableOfType(JkBuildParseException.class, () -> WorkspaceLoader.loadModules(dir, parsed));
        assertThat(e).as("a member is refused").isNotNull();
        return e;
    }

    private static Path write(Path dir, String text) throws Exception {
        Path file = dir.resolve("jk.toml").toAbsolutePath().normalize();
        Files.writeString(file, text);
        return file;
    }
}
