// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AddRemoveCommandTest {

    @Test
    void add_modifies_build_jk(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run("add", "com.fasterxml.jackson.core:jackson-databind:2.18.2", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.MAIN)).singleElement().satisfies(d -> {
            assertThat(d.library()).isEqualTo("jackson-databind");
            assertThat(d.module()).isEqualTo("com.fasterxml.jackson.core:jackson-databind");
        });
    }

    @Test
    void add_test_scope(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run("add", "org.junit.jupiter:junit-jupiter:6.1.0", "--test", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void add_with_structured_flags(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run(
                "add",
                "spring-web",
                "--group",
                "org.springframework.boot",
                "--name",
                "spring-boot-starter-web",
                "--ver",
                "3.4.0",
                "-C",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);

        String toml = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(toml)
                .contains("spring-web = { group = \"org.springframework.boot\", "
                        + "name = \"spring-boot-starter-web\", version = \"3.4.0\" }");

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.MAIN)).singleElement().satisfies(d -> {
            assertThat(d.library()).isEqualTo("spring-web");
            assertThat(d.module()).isEqualTo("org.springframework.boot:spring-boot-starter-web");
        });
    }

    @Test
    void add_then_remove_by_name(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        run("add", "com.foo.addrm:bar:1.0", "-C", tempDir.toString());
        int exit = run("remove", "bar", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void remove_accepts_coord_form_as_migration_aid(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        run("add", "com.foo.addrm:bar:1.0", "-C", tempDir.toString());
        int exit = run("remove", "com.foo.addrm:bar", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void remove_accepts_coord_with_version_and_at_version(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        run("add", "com.foo.addrm:bar:1.0", "-C", tempDir.toString());
        assertThat(run("remove", "com.foo.addrm:bar:9.9.9", "-C", tempDir.toString()))
                .isEqualTo(0);
        assertThat(JkBuildParser.parse(tempDir.resolve("jk.toml"))
                        .dependencies()
                        .of(Scope.MAIN))
                .isEmpty();

        run("add", "com.foo.addrm:baz:1.0", "-C", tempDir.toString());
        assertThat(run("remove", "baz@1.0.0", "-C", tempDir.toString())).isEqualTo(0);
        assertThat(JkBuildParser.parse(tempDir.resolve("jk.toml"))
                        .dependencies()
                        .of(Scope.MAIN))
                .isEmpty();
    }

    @Test
    void remove_path_form_uses_module_project_name(@TempDir Path tempDir) throws Exception {
        Path lib = tempDir.resolve("libb");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "libb"
                version = "0.2.0"
                """);
        Files.writeString(tempDir.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "app"
                version = "0.1.0"

                [workspace]
                modules = ["libb"]

                [dependencies]
                libb = { group = "cc.jumpkick", version = "=0.2.0" }
                """);

        assertThat(run("remove", "./libb", "-C", tempDir.toString())).isEqualTo(0);
        assertThat(JkBuildParser.parse(tempDir.resolve("jk.toml"))
                        .dependencies()
                        .of(Scope.MAIN))
                .isEmpty();
    }

    @Test
    void add_rejects_unparseable_coord(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        // A bare name that is not in the library catalog and has no --group
        // is a usage error.
        int exit = run("add", "not-a-coord", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void add_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run("add", "com.foo.addrm:bar:1.0", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void add_unknown_bare_name_suggests_close_catalog_matches(@TempDir Path tempDir) throws Exception {
        // `picocl` is a typo for `picocli`, which IS in the bundled
        // catalog. The "not in catalog" error should surface the
        // suggestion via LibraryCatalog.suggestionsFor — matching the
        // did-you-mean behavior of the parser.
        run("new", tempDir.toString());

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run("add", "picocl", "--ver", "1.0", "-C", tempDir.toString());
        } finally {
            System.setErr(origErr);
        }
        assertThat(exit).isEqualTo(64);
        String msg = stderr.toString(StandardCharsets.UTF_8);
        assertThat(msg).contains("not in the library catalog");
        assertThat(msg).contains("Did you mean:");
        assertThat(msg).contains("picocli");
    }

    @Test
    void remove_bare_name_prefers_manifest_key_over_shadowing_directory(@TempDir Path tempDir) throws Exception {
        // an unrelated checkout ./jackson (project name jackson-core) must not redirect
        // `jk remove jackson` away from the manifest dep of the same name.
        run("new", tempDir.toString());
        run("add", "com.foo.addrm:jackson:1.0", "-C", tempDir.toString());
        Path shadow = tempDir.resolve("jackson");
        Files.createDirectories(shadow);
        Files.writeString(shadow.resolve("jk.toml"), """
                group = "g"
                name = "jackson-core"
                version = "1.0.0"
                """);

        int exit = run("remove", "jackson", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(JkBuildParser.parse(tempDir.resolve("jk.toml"))
                        .dependencies()
                        .of(Scope.MAIN))
                .isEmpty();
    }

    @Test
    void add_path_form_promotes_a_plain_project_into_a_workspace(@TempDir Path tempDir) throws Exception {
        // Without the registration the dependency names a coordinate nobody published, so
        // `jk add ./libb` would leave a project that cannot lock.
        run("new", tempDir.toString());
        Path lib = tempDir.resolve("libb");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "libb"
                version = "0.2.0"
                """);
        assertThat(JkBuildParser.parse(tempDir.resolve("jk.toml")).isWorkspaceRoot())
                .isFalse();

        assertThat(run("add", "./libb", "-C", tempDir.toString())).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.isWorkspaceRoot()).isTrue();
        assertThat(parsed.workspace().modules()).containsExactly("libb");
        assertThat(parsed.dependencies().of(Scope.MAIN)).isNotEmpty();
    }

    @Test
    void remove_path_form_unregisters_the_workspace_module(@TempDir Path tempDir) throws Exception {
        // `jk add ./libb` registers [workspace].modules; `jk remove ./libb` must undo it.
        run("new", tempDir.toString());
        Path lib = tempDir.resolve("libb");
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("jk.toml"), """
                group = "cc.jumpkick"
                name = "libb"
                version = "0.2.0"
                """);
        assertThat(run("add", "./libb", "-C", tempDir.toString())).isEqualTo(0);
        assertThat(JkBuildParser.parse(tempDir.resolve("jk.toml")).workspace().modules())
                .contains("libb");

        assertThat(run("remove", "./libb", "-C", tempDir.toString())).isEqualTo(0);
        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
        assertThat(parsed.workspace().modules()).doesNotContain("libb");
    }

    @Test
    void add_with_coord_flags_wins_over_shadowing_directory(@TempDir Path tempDir) throws Exception {
        // explicit coordinate flags mean the library form even when ./<name> is a
        // directory — they must not be silently dropped by the path branch.
        run("new", tempDir.toString());
        Files.createDirectories(tempDir.resolve("spring-web"));
        int exit = run(
                "add",
                "spring-web",
                "--group",
                "org.springframework.boot",
                "--name",
                "spring-boot-starter-web",
                "--ver",
                "3.4.0",
                "-C",
                tempDir.toString());
        assertThat(exit).isEqualTo(0);
        String toml = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(toml)
                .contains("spring-web = { group = \"org.springframework.boot\", "
                        + "name = \"spring-boot-starter-web\", version = \"3.4.0\" }");
    }

    @Test
    void add_explicit_path_with_coord_flags_is_a_usage_error(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        Files.createDirectories(tempDir.resolve("mod"));
        int exit = run("add", "./mod", "--ver", "1.0", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
