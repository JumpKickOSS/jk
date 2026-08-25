// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Root-level {@code nerd-font} reading from ~/.config/jk/config.toml, leniently, plus the
 * {@code [repositories]} layer this file shares with the project manifest.
 */
class GlobalConfigTest {

    // Precedence is asserted through nerdFontMode, which is pure. nerdFont() resolves "auto" against
    // the real terminal, so asserting caps through it would make the test a property of this machine.

    @Test
    void reads_every_config_spelling(@TempDir Path dir) throws IOException {
        assertThat(mode(write(dir, "nerd-font = false\n"))).isEqualTo(NerdFontMode.OFF);
        assertThat(mode(write(dir, "nerd-font = true\n"))).isEqualTo(NerdFontMode.ON);
        assertThat(mode(write(dir, "nerd-font = \"auto\"\n"))).isEqualTo(NerdFontMode.AUTO);
        assertThat(mode(write(dir, "nerd-font = \"wedge\"\n"))).isEqualTo(NerdFontMode.WEDGE);
        assertThat(mode(write(dir, "nerd-font = \"pill\"\n"))).isEqualTo(NerdFontMode.PILL);
    }

    @Test
    void defaults_to_auto_when_unset_or_unparseable(@TempDir Path dir) throws IOException {
        assertThat(mode(dir.resolve("nope.toml"))).isEqualTo(NerdFontMode.AUTO); // no file
        assertThat(mode(write(dir, "# empty-ish\n"))).isEqualTo(NerdFontMode.AUTO);
        assertThat(mode(write(dir, "[cache]\nauto-prune = true\n"))).isEqualTo(NerdFontMode.AUTO); // no nerd-font
        // An unknown word is not an error: it falls through to the default, so a typo degrades to
        // detection rather than failing a build.
        assertThat(mode(write(dir, "nerd-font = \"sparkle\"\n"))).isEqualTo(NerdFontMode.AUTO);
        // Legacy [global] table is ignored (breaking change — keys must be root-level).
        assertThat(mode(write(dir, "[global]\nnerd-font = false\n"))).isEqualTo(NerdFontMode.AUTO);
    }

    @Test
    void jk_env_overrides_config(@TempDir Path dir) throws IOException {
        Path cfgOn = write(dir, "nerd-font = true\n");
        Path cfgOff = write(dir, "nerd-font = false\n");

        assertThat(GlobalConfig.nerdFontMode(cfgOn, "false", null)).isEqualTo(NerdFontMode.OFF);
        assertThat(GlobalConfig.nerdFontMode(cfgOn, "0", null)).isEqualTo(NerdFontMode.OFF);
        assertThat(GlobalConfig.nerdFontMode(cfgOn, "FALSE", null)).isEqualTo(NerdFontMode.OFF);
        assertThat(GlobalConfig.nerdFontMode(cfgOff, "true", null)).isEqualTo(NerdFontMode.ON);
        assertThat(GlobalConfig.nerdFontMode(cfgOff, "1", null)).isEqualTo(NerdFontMode.ON);
        assertThat(GlobalConfig.nerdFontMode(cfgOff, "yes", null)).isEqualTo(NerdFontMode.ON);
        assertThat(GlobalConfig.nerdFontMode(cfgOff, "on", null)).isEqualTo(NerdFontMode.ON);

        // the mode words are env-settable too
        assertThat(GlobalConfig.nerdFontMode(cfgOff, "wedge", null)).isEqualTo(NerdFontMode.WEDGE);
        assertThat(GlobalConfig.nerdFontMode(cfgOn, "pill", null)).isEqualTo(NerdFontMode.PILL);
        assertThat(GlobalConfig.nerdFontMode(cfgOn, "auto", null)).isEqualTo(NerdFontMode.AUTO);

        // unrecognised value falls through to the file
        assertThat(GlobalConfig.nerdFontMode(cfgOff, "maybe", null)).isEqualTo(NerdFontMode.OFF);
        assertThat(GlobalConfig.nerdFontMode(cfgOn, "maybe", null)).isEqualTo(NerdFontMode.ON);
    }

    @Test
    void host_nerd_font_env_sits_between_jk_env_and_config(@TempDir Path dir) throws IOException {
        Path cfgOn = write(dir, "nerd-font = true\n");
        Path cfgOff = write(dir, "nerd-font = false\n");

        // NERD_FONT beats the file
        assertThat(GlobalConfig.nerdFontMode(cfgOn, null, "0")).isEqualTo(NerdFontMode.OFF);
        assertThat(GlobalConfig.nerdFontMode(cfgOff, null, "1")).isEqualTo(NerdFontMode.ON);

        // JK_NERD_FONT beats NERD_FONT
        assertThat(GlobalConfig.nerdFontMode(cfgOff, "true", "false")).isEqualTo(NerdFontMode.ON);
        assertThat(GlobalConfig.nerdFontMode(cfgOn, "false", "true")).isEqualTo(NerdFontMode.OFF);
    }

    @Test
    void host_nerd_font_env_ignores_mode_words(@TempDir Path dir) throws IOException {
        // NERD_FONT is a cross-tool variable with no notion of jk's two axes, so a mode word there
        // is ignored rather than honoured — it must not silently mean something jk-specific.
        Path cfgOn = write(dir, "nerd-font = true\n");
        assertThat(GlobalConfig.nerdFontMode(cfgOn, null, "wedge")).isEqualTo(NerdFontMode.ON);
        assertThat(GlobalConfig.nerdFontMode(cfgOn, null, "auto")).isEqualTo(NerdFontMode.ON);
    }

    @Test
    void color_disabled_degrades_to_no_glyphs(@TempDir Path dir) throws IOException {
        // colorEnabled=false (--color never / NO_COLOR / --no-ansi / TERM=dumb / CI) must win over
        // everything: PUA glyphs without color produce misaligned or blank-box output.
        Path cfgOn = write(dir, "nerd-font = true\n");
        assertThat(GlobalConfig.nerdFont(cfgOn, null, null, false)).isEqualTo(NerdFontCaps.NONE);
        assertThat(GlobalConfig.nerdFont(cfgOn, "true", null, false)).isEqualTo(NerdFontCaps.NONE);
        assertThat(GlobalConfig.nerdFont(cfgOn, "wedge", null, false)).isEqualTo(NerdFontCaps.NONE);
        assertThat(GlobalConfig.nerdFont(cfgOn, null, "1", false)).isEqualTo(NerdFontCaps.NONE);
    }

    @Test
    void each_fixed_mode_grants_exactly_its_axes(@TempDir Path dir) throws IOException {
        Path none = dir.resolve("nope.toml");
        assertThat(GlobalConfig.nerdFont(none, "true", null, true)).isEqualTo(NerdFontCaps.ALL);
        assertThat(GlobalConfig.nerdFont(none, "false", null, true)).isEqualTo(NerdFontCaps.NONE);
        assertThat(GlobalConfig.nerdFont(none, "wedge", null, true)).isEqualTo(NerdFontCaps.WEDGE_ONLY);
        assertThat(GlobalConfig.nerdFont(none, "pill", null, true)).isEqualTo(NerdFontCaps.PILL_ONLY);
    }

    private static NerdFontMode mode(Path configFile) {
        return GlobalConfig.nerdFontMode(configFile, null, null);
    }

    @Test
    void reads_toolchain_engine_jdk_pin(@TempDir Path dir) throws IOException {
        Path cfg = write(dir, "[toolchain]\njdk = \"temurin-25\"\n");
        assertThat(GlobalConfig.engineJdkPin(cfg, null)).contains("temurin-25");
    }

    @Test
    void engine_jdk_pin_empty_when_unset_or_missing(@TempDir Path dir) throws IOException {
        assertThat(GlobalConfig.engineJdkPin(dir.resolve("nope.toml"), null)).isEmpty(); // no file
        assertThat(GlobalConfig.engineJdkPin(write(dir, "[toolchain]\n"), null)).isEmpty(); // table, no key
        assertThat(GlobalConfig.engineJdkPin(write(dir, "nerd-font = true\n"), null))
                .isEmpty(); // no [toolchain]
    }

    @Test
    void engine_jdk_pin_env_overrides_config(@TempDir Path dir) throws IOException {
        Path cfg = write(dir, "[toolchain]\njdk = \"temurin-25\"\n");
        assertThat(GlobalConfig.engineJdkPin(cfg, "graalvm-25")).contains("graalvm-25");
        assertThat(GlobalConfig.engineJdkPin(cfg, "  ")).contains("temurin-25"); // blank env ignored
    }

    private static Path write(Path dir, String content) throws IOException {
        Path f = Files.createTempFile(dir, "config", ".toml");
        Files.writeString(f, content);
        return f;
    }

    // ───────────────────────────────────────────────────────────────
    // [repositories]: one reader, two policies
    // ───────────────────────────────────────────────────────────────

    /**
     * The user-config layer and the project manifest read the same table with the same reader
     * ({@link RepositoryToml#repositories}), differing only in {@link RepositoryToml.OnBad}. The
     * same text therefore yields the same {@link RepositorySpec} on both sides.
     */
    @Test
    void a_well_formed_table_reads_identically_in_both_layers(@TempDir Path dir) throws IOException {
        String table = """
                [repositories.internal]
                url = "https://nexus.example.com/repo"
                groups = ["com.acme", "com.acme.*"]
                """;
        Path config = dir.resolve("config.toml");
        Files.writeString(config, table);
        List<RepositorySpec> global = GlobalConfig.repositories(config);
        List<RepositorySpec> project =
                JkBuildParser.parse("name = \"demo\"\n" + table).repositories();

        // Pinned to the declared values, not merely to each other: two readers that both went
        // blank would compare equal and prove nothing.
        assertThat(global).hasSize(1);
        assertThat(global.getFirst().name()).isEqualTo("internal");
        assertThat(global.getFirst().url()).hasToString("https://nexus.example.com/repo");
        assertThat(global.getFirst().groups()).containsExactly("com.acme", "com.acme.*");
        assertThat(project.getFirst().name()).isEqualTo(global.getFirst().name());
        assertThat(project.getFirst().url()).isEqualTo(global.getFirst().url());
        assertThat(project.getFirst().groups()).isEqualTo(global.getFirst().groups());
    }

    /**
     * Where they differ is the policy and nothing else: a manifest that lies fails the build; the
     * machine-local file drops the entry and carries on.
     */
    @Test
    void a_malformed_entry_is_rejected_by_the_manifest_and_skipped_by_the_user_config(@TempDir Path dir)
            throws IOException {
        String table = """
                [repositories.good]
                url = "https://ok.example.com/repo"

                [repositories.bad]
                groups = ["com.acme"]
                """;
        Path config = dir.resolve("config.toml");
        Files.writeString(config, table);
        assertThat(GlobalConfig.repositories(config))
                .extracting(RepositorySpec::name)
                .containsExactly("good");

        assertThatThrownBy(() -> JkBuildParser.parse("name = \"demo\"\n" + table))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("repositories.bad");
    }

    /** {@code jk-local} is the first-party install store: reserved in a manifest, ignored globally. */
    @Test
    void the_reserved_name_is_rejected_by_the_manifest_and_skipped_by_the_user_config(@TempDir Path dir)
            throws IOException {
        String table = "[repositories]\njk-local = \"https://elsewhere.example.com/\"\n";
        Path config = dir.resolve("config.toml");
        Files.writeString(config, table);
        assertThat(GlobalConfig.repositories(config)).isEmpty();
        assertThatThrownBy(() -> JkBuildParser.parse("name = \"demo\"\n" + table))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("jk-local");
    }

    /**
     * The two {@code ${VAR}} policies, from one {@link RepositoryToml.VarPolicy}. The user layer is
     * lenient — an unset variable stays literal rather than failing a build — while the manifest
     * defers expansion entirely, so a parsed manifest never carries a secret.
     */
    @Test
    void unset_variables_are_left_literal_in_the_user_layer(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("config.toml");
        Files.writeString(config, """
                [repositories.internal]
                url = "https://nexus.example.com/repo"
                token = "${JK_TEST_DEFINITELY_UNSET_TOKEN}"
                """);
        // Read through secret(), not toString(): a credential no longer prints what it holds.
        assertThat(GlobalConfig.repositories(config).getFirst().credential())
                .get()
                .extracting(c -> ((RepoCredential) c).secret())
                .isEqualTo("${JK_TEST_DEFINITELY_UNSET_TOKEN}");

        var manifest = JkBuildParser.parse("""
                name = "demo"
                [repositories.internal]
                url = "https://nexus.example.com/repo"
                token = "${JK_TEST_DEFINITELY_UNSET_TOKEN}"
                """);
        assertThat(manifest.repositories().getFirst().credential())
                .get()
                .extracting(c -> ((RepoCredential) c).secret())
                .isEqualTo("${JK_TEST_DEFINITELY_UNSET_TOKEN}");
    }
}
