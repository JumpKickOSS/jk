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
        assertThat(appToml).contains("libb = \"cc.jumpkick:libb:0.2.0\"");

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
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson = \"cc.jumpkick:jackson:1.0.0\"");
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
        assertThat(Files.readString(tmp.resolve("app/jk.toml"))).contains("libb = \"cc.jumpkick:libb:0.2.0\"");
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
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson = \"cc.jumpkick:jackson:1.0.0\"");
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
        // `=3.1.0` is the same exact selector as `3.1.0`; the file carries the bare form.
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson3-core = \"3.1.0\"");
    }

    /**
     * A manifest whose {@code [repositories]} names a local {@code file://} repository that
     * advertises the given versions, so a version-less add pins from it and never asks Central.
     */
    private static void projectWithLocalRepo(Path tmp, String group, String artifact, String... versions)
            throws IOException {
        Path repo = tmp.resolve("repo");
        Path dir = repo.resolve(group.replace('.', '/')).resolve(artifact);
        StringBuilder list = new StringBuilder();
        for (String v : versions) list.append("      <version>").append(v).append("</version>\n");
        write(dir.resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <versions>
                %s    </versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, list));
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"

                [repositories]
                local = "%s"

                [workspace]
                modules = []
                """.formatted(repo.toUri()));
    }

    @Test
    void add_group_artifact_trailing_colon_pins_the_newest_stable(@TempDir Path tmp) throws IOException {
        projectWithLocalRepo(tmp, "com.foo.emptyver", "bar", "1.0.0", "2.0.0", "2.1.0-RC1");

        int exit = Jk.execute("add", "com.foo.emptyver:bar:", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml")))
                .contains("bar = \"com.foo.emptyver:bar:2.0.0\"")
                .doesNotContain("latest");
    }

    @Test
    void add_bare_catalog_name_without_ver_pins_the_newest_stable(@TempDir Path tmp) throws IOException {
        // A bare name that IS in the library catalog resolves group + artifact; the version is
        // the newest stable the project's repositories advertise, written as a number.
        projectWithLocalRepo(tmp, "tools.jackson.core", "jackson-core", "3.0.0", "3.1.1", "3.2.0-rc1");

        int exit = Jk.execute("add", "jackson3-core", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);

        // A catalog library renders as the one-liner (name = version).
        String toml = Files.readString(tmp.resolve("jk.toml"));
        assertThat(toml).contains("jackson3-core = \"3.1.1\"").doesNotContain("latest");
    }

    @Test
    void add_bare_catalog_name_with_at_version_pins_that_version(@TempDir Path tmp) throws IOException {
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
    void add_bare_catalog_name_with_at_caret_keeps_the_float(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"
                """);

        int exit = Jk.execute("add", "jackson3-core@^3.1", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("jackson3-core = \"^3.1\"");
    }

    @Test
    void add_bare_catalog_name_with_at_latest_pins_the_newest_stable(@TempDir Path tmp) throws IOException {
        // `library@latest` is the same request as omitting the version.
        projectWithLocalRepo(tmp, "tools.jackson.core", "jackson-core", "3.0.0", "3.1.1");

        int exit = Jk.execute("add", "jackson3-core@latest", "-C", tmp.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tmp.resolve("jk.toml")))
                .contains("jackson3-core = \"3.1.1\"")
                .doesNotContain("latest");
    }

    @Test
    void add_without_version_offline_refuses_and_names_the_fix(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("jk.toml"), """
                group    = "cc.jumpkick"
                name     = "jk"
                version  = "0.1.0"
                """);
        String before = Files.readString(tmp.resolve("jk.toml"));

        int exit = Jk.execute("add", "--offline", "com.foo.off:bar", "-C", tmp.toString());
        assertThat(exit).isEqualTo(64);
        assertThat(Files.readString(tmp.resolve("jk.toml"))).isEqualTo(before);
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
        assertThat(toml).contains("bar = \"com.foo.add:bar:1.2.3\"");
        // Coord add must not touch the modules list.
        JkBuild root = JkBuildParser.parse(tmp.resolve("jk.toml"));
        assertThat(workspaceOf(root).modules()).containsExactly("app");
    }

    private static Workspace workspaceOf(JkBuild build) {
        assertThat(build.workspace()).as("[workspace] table").isNotNull();
        return Objects.requireNonNull(build.workspace());
    }
}
