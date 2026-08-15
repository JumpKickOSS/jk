// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * precedence and search roots for {@code.env}.
 *
 * <p>The two decisions worth pinning: the real environment beats {@code.env} (Node dotenv / Docker
 * Compose convention — the file supplies defaults, so a shell or CI variable can still override it),
 * and the search stops at the workspace root rather than walking up to a git root.
 */
class EnvLookupTest {

    /** A workspace with one module, each able to carry a .env. */
    private static Path workspace(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "ws"
                version = "1.0.0"

                [workspace]
                modules = ["mod"]
                """);
        Path module = Files.createDirectories(tmp.resolve("mod"));
        Files.writeString(module.resolve("jk.toml"), """
                group = "com.example"
                name  = "mod"
                version = "1.0.0"
                """);
        return module;
    }

    @Test
    void an_edited_env_file_is_picked_up_despite_the_read_memo(@TempDir Path tmp) throws Exception {
        Path module = workspace(tmp);
        Files.writeString(module.resolve(".env"), "TOKEN=first\n");
        assertThat(EnvLookup.forModule(module, name -> null).get("TOKEN")).isEqualTo("first");

        // Redaction resolves per output line, so reads are memoizedbut an edit
        // (new size/mtime) must invalidate.
        Files.writeString(module.resolve(".env"), "TOKEN=second-longer\n");
        Files.setLastModifiedTime(module.resolve(".env"), FileTime.fromMillis(System.currentTimeMillis() + 2_000));

        assertThat(EnvLookup.forModule(module, name -> null).get("TOKEN")).isEqualTo("second-longer");
    }

    @Test
    void repeated_lookups_reuse_one_redactor_instance(@TempDir Path tmp) throws Exception {
        Path module = workspace(tmp);
        Files.writeString(module.resolve(".env"), "SECRET=hunter2-hunter2\n");

        var first = SecretRedactor.from(EnvLookup.forModule(module, name -> null));
        var second = SecretRedactor.from(EnvLookup.forModule(module, name -> null));

        // Same value set → same memoized instance: per-line redaction must not rebuild.
        assertThat(second).isSameAs(first);
        assertThat(first.redact("token is hunter2-hunter2")).doesNotContain("hunter2");
    }

    @Test
    void the_module_env_beats_the_workspace_env(@TempDir Path tmp) throws Exception {
        Path module = workspace(tmp);
        Files.writeString(tmp.resolve(".env"), "SHARED=from-workspace\nONLY_WS=ws\n");
        Files.writeString(module.resolve(".env"), "SHARED=from-module\n");

        var env = EnvLookup.forModule(module, name -> null);

        assertThat(env.get("SHARED")).isEqualTo("from-module");
        assertThat(env.get("ONLY_WS")).isEqualTo("ws");
    }

    @Test
    void the_real_environment_beats_dot_env(@TempDir Path tmp) throws Exception {
        Path module = workspace(tmp);
        Files.writeString(module.resolve(".env"), "MODE=from-file\n");

        var env = EnvLookup.forModule(module, name -> "MODE".equals(name) ? "from-shell" : null);

        // .env supplies defaults; `MODE=from-shell jk build` and CI variables must still win.
        assertThat(env.get("MODE")).isEqualTo("from-shell");
    }

    @Test
    void a_value_only_in_dot_env_is_still_visible(@TempDir Path tmp) throws Exception {
        Path module = workspace(tmp);
        Files.writeString(module.resolve(".env"), "TOKEN=abc\n");

        assertThat(EnvLookup.forModule(module, name -> null).get("TOKEN")).isEqualTo("abc");
    }

    @Test
    void an_unknown_name_is_null(@TempDir Path tmp) throws Exception {
        Path module = workspace(tmp);
        assertThat(EnvLookup.forModule(module, name -> null).get("NOPE")).isNull();
    }

    @Test
    void file_sourced_values_are_identifiable_so_they_can_be_treated_as_secret(@TempDir Path tmp) throws Exception {
        Path module = workspace(tmp);
        Files.writeString(module.resolve(".env"), "TOKEN=abc\nMODE=file\n");

        var env = EnvLookup.forModule(module, name -> "MODE".equals(name) ? "shell" : null);

        assertThat(env.isFromFile("TOKEN")).isTrue();
        // Shadowed by the real environment, so its effective value did not come from the file.
        assertThat(env.isFromFile("MODE")).isFalse();
        assertThat(env.isFromFile("ABSENT")).isFalse();
        assertThat(env.fileNames()).containsExactlyInAnyOrder("TOKEN", "MODE");
    }

    @Test
    void a_standalone_project_reads_only_its_own_env(@TempDir Path tmp) throws Exception {
        // No [workspace] anywhere: the module is the whole project.
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "com.example"
                name  = "solo"
                version = "1.0.0"
                """);
        Files.writeString(tmp.resolve(".env"), "FOO=bar\n");

        assertThat(EnvLookup.forModule(tmp, name -> null).get("FOO")).isEqualTo("bar");
    }

    @Test
    void nothing_is_read_from_above_the_workspace_root(@TempDir Path tmp) throws Exception {
        // A.env in a parent directory — a git root, say — must not be picked up: a build has to
        // behave the same from a tarball as from a checkout.
        Path outer = Files.createDirectories(tmp.resolve("outer"));
        Files.writeString(outer.resolve(".env"), "LEAKED=yes\n");
        Path module = workspace(Files.createDirectories(outer.resolve("ws")));

        assertThat(EnvLookup.forModule(module, name -> null).get("LEAKED")).isNull();
    }

    @Test
    void as_function_feeds_the_parser(@TempDir Path tmp) throws Exception {
        Path module = workspace(tmp);
        Files.writeString(module.resolve(".env"), "REPO_USER=alice\n");
        var fn = EnvLookup.forModule(module, name -> null).asFunction();
        assertThat(fn.apply("REPO_USER")).isEqualTo("alice");
    }

    @Test
    void of_builds_a_file_only_lookup() {
        var env = EnvLookup.of(Map.of("A", "1"));
        assertThat(env.get("A")).isEqualTo("1");
        assertThat(env.isFromFile("A")).isTrue();
    }
}
