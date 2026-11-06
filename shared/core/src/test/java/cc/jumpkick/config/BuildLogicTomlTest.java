// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [build].logic} had two implementations — one in the engine that decides what runs, one in
 * the CLI that decides what {@code jk tasks} lists — and nothing kept them the same. These cases
 * pin the single owner's whole vocabulary: the key names, the off spellings, the default directory
 * and the containment rule.
 */
class BuildLogicTomlTest {

    @TempDir
    Path dir;

    private void manifest(String toml) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), toml);
    }

    @Test
    void default_directory_when_the_key_is_absent() throws IOException {
        manifest("name = \"demo\"\n");
        Files.createDirectories(dir.resolve(BuildLogicToml.DEFAULT_DIR));
        assertThat(BuildLogicToml.resolve(dir).orElseThrow().dir()).isEqualTo(dir.resolve(BuildLogicToml.DEFAULT_DIR));
    }

    @Test
    void empty_when_the_default_directory_does_not_exist() throws IOException {
        manifest("name = \"demo\"\n");
        assertThat(BuildLogicToml.resolve(dir)).isEmpty();
    }

    @Test
    void declared_directory() throws IOException {
        manifest("""
                [build]
                logic = "buildsrc"
                """);
        Files.createDirectory(dir.resolve("buildsrc"));
        var logic = BuildLogicToml.resolve(dir).orElseThrow();
        assertThat(logic.dir()).isEqualTo(dir.resolve("buildsrc"));
    }

    /**
     * The off spellings are the general truth set ({@link EnvValues#parseBool}) plus this key's own
     * two. Both readers had their own copy of this list; there is one now.
     */
    @Test
    void every_off_spelling_disables_even_when_the_directory_exists() throws IOException {
        Files.createDirectories(dir.resolve(BuildLogicToml.DEFAULT_DIR));
        for (String off : new String[] {"false", "no", "0", "off", "none", "disable", "OFF", " None "}) {
            manifest("[build]\nlogic = \"" + off.strip() + "\"\n");
            assertThat(BuildLogicToml.resolve(dir))
                    .as("logic = %s disables build logic", off)
                    .isEmpty();
        }
    }

    @Test
    void a_directory_outside_the_project_root_is_refused() throws IOException {
        manifest("""
                [build]
                logic = "../elsewhere"
                """);
        assertThatThrownBy(() -> BuildLogicToml.resolve(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must stay under the project root");
    }

    /**
     * The documented narrowing. {@link TomlScan} cannot see a key inside an inline table, so it
     * reads as absent and the default applies — the same answer on both sides, which is the
     * property that matters. If this ever has to change it changes for the engine and the CLI at
     * once, because there is only one reader to change.
     */
    @Test
    void an_inline_build_table_reads_as_absent_and_defaults() throws IOException {
        manifest("build = { logic = \"buildsrc\" }\n");
        Files.createDirectory(dir.resolve("buildsrc"));
        Files.createDirectories(dir.resolve(BuildLogicToml.DEFAULT_DIR));
        assertThat(BuildLogicToml.resolve(dir).orElseThrow().dir()).isEqualTo(dir.resolve(BuildLogicToml.DEFAULT_DIR));
    }

    @Test
    void has_stem_sees_gate_kts_and_suffixes() throws IOException {
        manifest("name = \"demo\"\n");
        Files.createDirectories(dir.resolve(".jk/scripts"));
        assertThat(BuildLogicToml.hasStem(dir, "guard")).isFalse();
        Files.writeString(dir.resolve(".jk/scripts/guard.groovy"), "//\n");
        assertThat(BuildLogicToml.hasStem(dir, "guard")).isTrue();
        assertThat(BuildLogicToml.hasStem(dir, "after-build")).isFalse();
    }

    @Test
    void only_jk_scripts_is_build_logic_and_jk_alone_is_not() throws IOException {
        manifest("name = \"demo\"\n");
        Files.createDirectories(dir.resolve(".jk/ci"));
        Files.createDirectories(dir.resolve("jk"));
        assertThat(BuildLogicToml.resolve(dir)).isEmpty();
        assertThat(BuildLogicToml.dirs(dir)).isEmpty();
        Files.createDirectories(dir.resolve(".jk/scripts"));
        assertThat(BuildLogicToml.dirs(dir)).containsExactly(dir.resolve(".jk/scripts"));
    }

    @Test
    void a_script_left_at_the_top_of_jk_is_named_and_not_read() throws IOException {
        manifest("name = \"demo\"\n");
        assertThat(BuildLogicToml.strayScriptsNote(dir)).isNull();
        Files.createDirectories(dir.resolve(".jk/scripts"));
        Files.writeString(dir.resolve(".jk/guard.groovy"), "//\n");
        Files.writeString(dir.resolve(".jk/before-compile.kts"), "//\n");
        Files.writeString(dir.resolve(".jk/notes.txt"), "not a script\n");
        assertThat(BuildLogicToml.hasStem(dir, "guard")).isFalse();
        assertThat(BuildLogicToml.strayScripts(dir)).containsExactly("before-compile.kts", "guard.groovy");
        assertThat(BuildLogicToml.strayScriptsNote(dir))
                .isEqualTo("before-compile.kts, guard.groovy are at the top of .jk/ and will not run:"
                        + " build scripts live in .jk/scripts/");
    }
}
