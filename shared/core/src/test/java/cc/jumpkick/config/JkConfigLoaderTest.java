// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkConfigLoaderTest {

    @Test
    void empty_leaves_every_setting_unset() {
        JkConfig empty = JkConfig.empty();
        assertThat(empty.color()).isNull();
        assertThat(empty.offline()).isNull();
        assertThat(empty.directory()).isNull();
    }

    @Test
    void mergedWith_overlays_set_values() {
        JkConfig base = JkConfig.empty().withColor(JkConfig.ColorChoice.NEVER).withOffline(true);
        JkConfig over = JkConfig.empty().withColor(JkConfig.ColorChoice.ALWAYS).withNoProgress(true);
        JkConfig merged = base.mergedWith(over);
        assertThat(merged.color()).isEqualTo(JkConfig.ColorChoice.ALWAYS); // over wins
        assertThat(merged.offline()).isTrue(); // base passes through
        assertThat(merged.noProgress()).isTrue(); // over sets it
    }

    @Test
    void parses_project_toml_config_section(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, """
            [config]
            color = "always"
            offline = true
            quiet = false
            """);
        JkConfig loaded = JkConfigLoader.loadTomlOrEmpty(toml);
        assertThat(loaded.color()).isEqualTo(JkConfig.ColorChoice.ALWAYS);
        assertThat(loaded.offline()).isTrue();
        assertThat(loaded.quiet()).isFalse();
    }

    @Test
    void parses_notify_no_osc_no_ansi_from_toml(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, """
            [config]
            notify = "always"
            no-osc = true
            no-ansi = false
            no-progress = true
            """);
        JkConfig loaded = JkConfigLoader.loadTomlOrEmpty(toml);
        assertThat(loaded.notifyPolicy()).isEqualTo(JkConfig.NotifyChoice.ALWAYS);
        assertThat(loaded.noOsc()).isTrue();
        assertThat(loaded.noAnsi()).isFalse();
        assertThat(loaded.noProgress()).isTrue();
    }

    @Test
    void parses_notify_boolean_true_false_as_always_never(@TempDir Path tempDir) throws IOException {
        Path always = tempDir.resolve("always.toml");
        Files.writeString(always, "[config]\nnotify = true\n");
        assertThat(JkConfigLoader.loadTomlOrEmpty(always).notifyPolicy()).isEqualTo(JkConfig.NotifyChoice.ALWAYS);

        Path never = tempDir.resolve("never.toml");
        Files.writeString(never, "[config]\nnotify = false\n");
        assertThat(JkConfigLoader.loadTomlOrEmpty(never).notifyPolicy()).isEqualTo(JkConfig.NotifyChoice.NEVER);

        Path auto = tempDir.resolve("auto.toml");
        Files.writeString(auto, "[config]\nnotify = \"auto\"\n");
        assertThat(JkConfigLoader.loadTomlOrEmpty(auto).notifyPolicy()).isEqualTo(JkConfig.NotifyChoice.AUTO);
    }

    @Test
    void env_var_notify_and_no_osc() {
        JkConfig env = JkConfigLoader.loadFromEnv(Map.of(
                "JK_NOTIFY", "never",
                "JK_NO_OSC", "true",
                "JK_NO_ANSI", "1")::get);
        assertThat(env.notifyPolicy()).isEqualTo(JkConfig.NotifyChoice.NEVER);
        assertThat(env.noOsc()).isTrue();
        assertThat(env.noAnsi()).isTrue();
    }

    @Test
    void notify_choice_parse_accepts_aliases() {
        assertThat(JkConfig.NotifyChoice.parse("ALWAYS")).hasValue(JkConfig.NotifyChoice.ALWAYS);
        assertThat(JkConfig.NotifyChoice.parse("yes")).hasValue(JkConfig.NotifyChoice.ALWAYS);
        assertThat(JkConfig.NotifyChoice.parse("0")).hasValue(JkConfig.NotifyChoice.NEVER);
        assertThat(JkConfig.NotifyChoice.parse("auto")).hasValue(JkConfig.NotifyChoice.AUTO);
        assertThat(JkConfig.NotifyChoice.parse("maybe")).isEmpty();
    }

    @Test
    void env_var_color_overrides_no_color() {
        // JK_COLOR=auto, NO_COLOR=1 — explicit JK_COLOR wins.
        JkConfig fromEnv = JkConfigLoader.loadFromEnv(Map.of(
                "JK_COLOR", "auto",
                "NO_COLOR", "1")::get);
        assertThat(fromEnv.color()).isEqualTo(JkConfig.ColorChoice.AUTO);
    }

    @Test
    void env_var_no_color_alone_means_never() {
        JkConfig fromEnv = JkConfigLoader.loadFromEnv(Map.of("NO_COLOR", "1")::get);
        assertThat(fromEnv.color()).isEqualTo(JkConfig.ColorChoice.NEVER);
    }

    @Test
    void env_var_boolean_truthy_values() {
        JkConfig env = JkConfigLoader.loadFromEnv(Map.of(
                "JK_OFFLINE", "true",
                "JK_NO_PROGRESS", "1",
                "JK_QUIET", "yes",
                "JK_VERBOSE", "off")::get);
        assertThat(env.offline()).isTrue();
        assertThat(env.noProgress()).isTrue();
        assertThat(env.quiet()).isTrue();
        assertThat(env.verbose()).isFalse();
    }

    @Test
    void parses_build_output_from_toml(@TempDir Path tempDir) throws IOException {
        Path on = tempDir.resolve("on.toml");
        Files.writeString(on, """
            [config]
            build-output = true
            """);
        assertThat(JkConfigLoader.loadTomlOrEmpty(on).buildOutput()).isTrue();
        assertThat(JkConfigLoader.loadTomlOrEmpty(on).buildOutputOr(false)).isTrue();

        Path off = tempDir.resolve("off.toml");
        Files.writeString(off, "[config]\nbuild-output = false\n");
        assertThat(JkConfigLoader.loadTomlOrEmpty(off).buildOutput()).isFalse();
        assertThat(JkConfig.empty().buildOutputOr(false)).isFalse();
    }

    @Test
    void env_var_build_output() {
        JkConfig env = JkConfigLoader.loadFromEnv(Map.of("JK_BUILD_OUTPUT", "true")::get);
        assertThat(env.buildOutput()).isTrue();
        assertThat(env.buildOutputOr(false)).isTrue();
    }

    @Test
    void find_project_config_walks_upward(@TempDir Path tempDir) throws IOException {
        Path nested = tempDir.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(nested);
        Path projectToml = tempDir.resolve("a").resolve("jk.toml");
        Files.writeString(projectToml, "[config]\ncolor = \"always\"\n");

        Path found = ConfigSources.findProjectConfig(nested);
        assertThat(found).isEqualTo(projectToml);
    }

    @Test
    void no_config_short_circuits_files_but_keeps_env(@TempDir Path tempDir) throws IOException {
        Path projectToml = tempDir.resolve("jk.toml");
        // offline is file-only here; ambient NO_COLOR (CI/agents) may still set color via env.
        Files.writeString(projectToml, "[config]\ncolor = \"always\"\noffline = true\n");

        JkConfig loaded = JkConfigLoader.load(tempDir, /* noConfig= */ true, Optional.empty());
        // Project file was ignored: file-only offline must not appear.
        assertThat(loaded.offline()).isNull();
        // File said ALWAYS; must not leak. Env may still set color (NO_COLOR → NEVER).
        assertThat(loaded.color()).isNotEqualTo(JkConfig.ColorChoice.ALWAYS);
    }
    /**
     * "First of force, rebuild that is set" is the wrong rule: with force set-and-false (every wire
     * decode), it never consults rebuild — this exact bug shipped briefly.
     */
    @Test
    void rebuildOr_sees_rebuild_even_when_force_is_present_and_false() {
        JkConfig wireShaped = JkConfig.empty()
                .withOffline(false)
                .withRebuild(true)
                .withVerbose(false)
                // force: set-and-false, as every wire decode materializes it
                .withForce(false);
        assertThat(wireShaped.rebuildOr(false)).isTrue();
        assertThat(wireShaped.forceOr(false)).isFalse();

        JkConfig unset = JkConfig.empty();
        assertThat(unset.rebuildOr(true)).isTrue(); // both empty -> fallback
        assertThat(unset.rebuildOr(false)).isFalse();
    }
}
