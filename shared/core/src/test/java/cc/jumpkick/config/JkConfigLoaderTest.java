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
    void empty_returns_all_optionals_empty() {
        JkConfig empty = JkConfig.empty();
        assertThat(empty.color()).isEmpty();
        assertThat(empty.offline()).isEmpty();
        assertThat(empty.directory()).isEmpty();
    }

    @Test
    void mergedWith_overlays_set_values() {
        JkConfig base = new JkConfig(
                Optional.of(JkConfig.ColorChoice.NEVER),
                Optional.of(true),
                Optional.empty(), // rebuild
                Optional.empty(), // noProgress
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(), // force
                Optional.empty(),
                Optional.empty(),
                Optional.empty()); // noAnsi
        JkConfig over = new JkConfig(
                Optional.of(JkConfig.ColorChoice.ALWAYS),
                Optional.empty(),
                Optional.empty(), // rebuild
                Optional.of(true), // noProgress — over sets it
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(), // force
                Optional.empty(),
                Optional.empty(),
                Optional.empty()); // noAnsi
        JkConfig merged = base.mergedWith(over);
        assertThat(merged.color()).hasValue(JkConfig.ColorChoice.ALWAYS); // over wins
        assertThat(merged.offline()).hasValue(true); // base passes through
        assertThat(merged.noProgress()).hasValue(true); // over sets it
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
        assertThat(loaded.color()).hasValue(JkConfig.ColorChoice.ALWAYS);
        assertThat(loaded.offline()).hasValue(true);
        assertThat(loaded.quiet()).hasValue(false);
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
        assertThat(loaded.notifyPolicy()).hasValue(JkConfig.NotifyChoice.ALWAYS);
        assertThat(loaded.noOsc()).hasValue(true);
        assertThat(loaded.noAnsi()).hasValue(false);
        assertThat(loaded.noProgress()).hasValue(true);
    }

    @Test
    void parses_notify_boolean_true_false_as_always_never(@TempDir Path tempDir) throws IOException {
        Path always = tempDir.resolve("always.toml");
        Files.writeString(always, "[config]\nnotify = true\n");
        assertThat(JkConfigLoader.loadTomlOrEmpty(always).notifyPolicy()).hasValue(JkConfig.NotifyChoice.ALWAYS);

        Path never = tempDir.resolve("never.toml");
        Files.writeString(never, "[config]\nnotify = false\n");
        assertThat(JkConfigLoader.loadTomlOrEmpty(never).notifyPolicy()).hasValue(JkConfig.NotifyChoice.NEVER);

        Path auto = tempDir.resolve("auto.toml");
        Files.writeString(auto, "[config]\nnotify = \"auto\"\n");
        assertThat(JkConfigLoader.loadTomlOrEmpty(auto).notifyPolicy()).hasValue(JkConfig.NotifyChoice.AUTO);
    }

    @Test
    void env_var_notify_and_no_osc() {
        JkConfig env = JkConfigLoader.loadFromEnv(Map.of(
                "JK_NOTIFY", "never",
                "JK_NO_OSC", "true",
                "JK_NO_ANSI", "1")::get);
        assertThat(env.notifyPolicy()).hasValue(JkConfig.NotifyChoice.NEVER);
        assertThat(env.noOsc()).hasValue(true);
        assertThat(env.noAnsi()).hasValue(true);
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
        assertThat(fromEnv.color()).hasValue(JkConfig.ColorChoice.AUTO);
    }

    @Test
    void env_var_no_color_alone_means_never() {
        JkConfig fromEnv = JkConfigLoader.loadFromEnv(Map.of("NO_COLOR", "1")::get);
        assertThat(fromEnv.color()).hasValue(JkConfig.ColorChoice.NEVER);
    }

    @Test
    void env_var_boolean_truthy_values() {
        JkConfig env = JkConfigLoader.loadFromEnv(Map.of(
                "JK_OFFLINE", "true",
                "JK_NO_PROGRESS", "1",
                "JK_QUIET", "yes",
                "JK_VERBOSE", "off")::get);
        assertThat(env.offline()).hasValue(true);
        assertThat(env.noProgress()).hasValue(true);
        assertThat(env.quiet()).hasValue(true);
        assertThat(env.verbose()).hasValue(false);
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
        assertThat(loaded.offline()).isEmpty();
        // File said ALWAYS; must not leak. Env may still set color (NO_COLOR → NEVER).
        assertThat(loaded.color()).isNotEqualTo(Optional.of(JkConfig.ColorChoice.ALWAYS));
    }
    /**
     * Optional.or short-circuits on PRESENCE: with force present-and-false (every wire decode),
     * a naive force.or(() -> rebuild) never consults rebuild — this exact bug shipped briefly.
     */
    @Test
    void rebuildOr_sees_rebuild_even_when_force_is_present_and_false() {
        JkConfig wireShaped = new JkConfig(
                Optional.empty(),
                Optional.of(false), // offline
                Optional.of(true), // rebuild
                Optional.empty(),
                Optional.empty(),
                Optional.of(false), // verbose
                Optional.empty(),
                Optional.of(false), // force: present-and-false, as every wire decode materializes it
                Optional.empty(), // noAnsi
                Optional.empty(), // noOsc
                Optional.empty()); // notifyPolicy
        assertThat(wireShaped.rebuildOr(false)).isTrue();
        assertThat(wireShaped.forceOr(false)).isFalse();

        JkConfig unset = JkConfig.empty();
        assertThat(unset.rebuildOr(true)).isTrue(); // both empty -> fallback
        assertThat(unset.rebuildOr(false)).isFalse();
    }
}
