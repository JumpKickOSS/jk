// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Workspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AddCommandTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String module(String artifact, String version) {
        return """
                group    = "cc.jumpkick"
                name     = "%s"
                version  = "%s"
                """.formatted(artifact, version);
    }

    @Test
    void add_path_adds_dep_edge_and_registers_module(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["app"]
                """);
        write(tmp.resolve("app/jk.toml"), module("app", "0.1.0"));
        write(tmp.resolve("libb/jk.toml"), module("libb", "0.2.0"));

        int exit = Jk.execute("add", "../libb", "-C", tmp.resolve("app").toString());
        assertThat(exit).isEqualTo(0);

        // Pinned dep edge into the current (app) project; artifact omitted since
        // it matches the key.
        String appToml = Files.readString(tmp.resolve("app/jk.toml"));
        assertThat(appToml).contains("libb = { group = \"cc.jumpkick\", version = \"=0.2.0\" }");

        // libb is now registered in the workspace root.
        JkBuild root = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(workspaceOf(root).modules()).containsExactly("app", "libb");
    }

    @Test
    void add_colon_prefixed_name_is_a_local_module(@TempDir Path tmp) throws IOException {
        // `:jackson` — explicit local marker, no path separator.
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["core"]
                """);
        write(tmp.resolve("jackson/jk.toml"), module("jackson", "1.0.0"));

        int exit = Jk.execute("add", ":jackson", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml")))
                .contains("jackson = { group = \"cc.jumpkick\", version = \"=1.0.0\" }");
        assertThat(workspaceOf(JkBuildParser.parse(tmp.resolve("jk.toml"))).modules())
                .containsExactly("core", "jackson");
    }

    @Test
    void add_trailing_slash_is_a_local_module(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["core"]
                """);
        write(tmp.resolve("jackson/jk.toml"), module("jackson", "2.0.0"));

        int exit = Jk.execute("add", "jackson/", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(workspaceOf(JkBuildParser.parse(tmp.resolve("jk.toml"))).modules())
                .containsExactly("core", "jackson");
    }

    @Test
    void add_backslash_path_within_workspace_is_a_local_module(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["app"]
                """);
        write(tmp.resolve("app/jk.toml"), module("app", "0.1.0"));
        write(tmp.resolve("libb/jk.toml"), module("libb", "0.2.0"));

        // Windows-style separators, resolved relative to the module dir.
        int exit = Jk.execute("add", "..\\libb", "-C", tmp.resolve("app").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("app/jk.toml")))
                .contains("libb = { group = \"cc.jumpkick\", version = \"=0.2.0\" }");
        assertThat(workspaceOf(JkBuildParser.parse(tmp.resolve("jk.toml"))).modules())
                .containsExactly("app", "libb");
    }

    @Test
    void add_bare_name_is_path_when_relative_dir_exists(@TempDir Path tmp) throws IOException {
        // Bare token + existing relative directory → local module (not catalog library).
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["core"]
                """);
        write(tmp.resolve("jackson/jk.toml"), module("jackson", "1.0.0"));

        int exit = Jk.execute("add", "jackson", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml")))
                .contains("jackson = { group = \"cc.jumpkick\", version = \"=1.0.0\" }");
        assertThat(workspaceOf(JkBuildParser.parse(tmp.resolve("jk.toml"))).modules())
                .containsExactly("core", "jackson");
    }

    @Test
    void add_bare_name_is_library_when_no_relative_dir(@TempDir Path tmp) throws IOException {
        // No directory by that name → library short name. Unknown catalog entry → usage error.
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);

        int exit = Jk.execute("add", "not-a-catalog-lib-xyz", "-C", tmp.toString());
        assertThat(exit).isEqualTo(64);
        assertThat(workspaceOf(JkBuildParser.parse(tmp.resolve("jk.toml"))).modules())
                .isEmpty();
    }

    @Test
    void add_at_version_is_library_even_when_dir_exists(@TempDir Path tmp) throws IOException {
        // name@version is always a library — path disambiguation does not apply.
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);
        write(tmp.resolve("jackson3-core/jk.toml"), module("jackson3-core", "9.9.9"));

        int exit = Jk.execute("add", "jackson3-core@3.1.0", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        // Catalog library, not the local module's version.
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson3-core = \"3.1.0\"");
        assertThat(workspaceOf(JkBuildParser.parse(tmp.resolve("jk.toml"))).modules())
                .isEmpty();
    }

    @Test
    void add_at_exact_version_selector(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"
                """);

        int exit = Jk.execute("add", "jackson3-core@=3.1.0", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson3-core = \"=3.1.0\"");
    }

    @Test
    void add_group_artifact_trailing_colon_is_latest(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"
                """);

        int exit = Jk.execute("add", "com.foo.emptyver:bar:", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml")))
                .contains("bar = { group = \"com.foo.emptyver\", version = \"latest\" }");
    }

    @Test
    void add_bare_catalog_name_without_ver_defaults_to_latest(@TempDir Path tmp) throws IOException {
        // A bare name that IS in the library catalog resolves group + artifact
        // and defaults to floating "latest" when --ver is omitted, matching the
        // group:artifact coord form. Resolution happens later at `jk lock`.
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);

        int exit = Jk.execute("add", "jackson3-core", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);

        // Because jackson3-core is a known catalog library, the editor renders
        // the short form (library name = version) rather than the expanded coord.
        String toml = Files.readString(tmp.resolve("jk.toml"));
        assertThat(toml).contains("jackson3-core = \"latest\"");
    }

    @Test
    void add_bare_catalog_name_with_at_version_pins_floating(@TempDir Path tmp) throws IOException {
        // `library@version` resolves the library and uses the @version as a
        // caret-floating selector, matching group:artifact@version.
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);

        int exit = Jk.execute("add", "jackson3-core@3.1.0", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson3-core = \"3.1.0\"");
    }

    @Test
    void add_bare_catalog_name_with_at_latest_is_latest(@TempDir Path tmp) throws IOException {
        // `library@latest` is equivalent to omitting the version.
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = []
                """);

        int exit = Jk.execute("add", "jackson3-core@latest", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson3-core = \"latest\"");
    }

    @Test
    void add_maven_coord_is_unchanged_and_leaves_workspace_alone(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [workspace]
                modules = ["app"]
                """);

        int exit = Jk.execute("add", "com.foo.add:bar:1.2.3", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);

        String toml = Files.readString(tmp.resolve("jk.toml"));
        assertThat(toml).contains("bar = { group = \"com.foo.add\", version = \"=1.2.3\" }");
        // Coord add must not touch the modules list.
        JkBuild root = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(workspaceOf(root).modules()).containsExactly("app");
    }

    private static Workspace workspaceOf(JkBuild build) {
        assertThat(build.workspace()).as("[workspace] table").isNotNull();
        return Objects.requireNonNull(build.workspace());
    }
}
