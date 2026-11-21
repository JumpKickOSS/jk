// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.WorkingDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The global pre-scans that run before dispatch read only jk's own args: nothing past a literal
 * {@code --}, nothing after a passthrough command's name. {@code jk run . -- -q} leaves the session
 * exactly as it found it.
 */
class JkOwnArgsScanTest {

    private static final Path UNSET = Path.of("unset-sentinel");

    private Session original;

    @BeforeEach
    void keepSession() {
        original = SessionContext.installed();
    }

    @AfterEach
    void restoreSession() {
        SessionContext.install(original);
    }

    @Test
    void list_is_rewritten_to_help_only_within_jks_own_args() {
        String[] forTheTool = {"run", "mytool", "--", "--list"};
        assertThat(Jk.rewriteListToHelp(forTheTool)).isSameAs(forTheTool);

        String[] forMaven = {"mvn", "--list"};
        assertThat(Jk.rewriteListToHelp(forMaven)).isSameAs(forMaven);

        assertThat(Jk.rewriteListToHelp(new String[] {"--list"})).containsExactly("--help");
        assertThat(Jk.rewriteListToHelp(new String[] {"jdk", "--list"})).containsExactly("jdk", "--help");
        assertThat(Jk.rewriteListToHelp(new String[] {"skill", "--list"})).containsExactly("skill", "--list");
    }

    @Test
    void flags_after_a_double_dash_leave_the_session_untouched() {
        Jk.applyCliOverrides(new String[] {"run", ".", "--", "-q", "-C", "/elsewhere", "--offline"});

        var cfg = SessionContext.current().config();
        assertThat(cfg.quietOr(false)).isFalse();
        assertThat(cfg.offlineOr(false)).isFalse();
        assertThat(cfg.dirOr(UNSET)).isEqualTo(UNSET);
    }

    @Test
    void flags_after_a_passthrough_command_leave_the_session_untouched() {
        Jk.applyCliOverrides(new String[] {"mvn", "-C", "install", "-q"});

        var cfg = SessionContext.current().config();
        assertThat(cfg.dirOr(UNSET)).isEqualTo(UNSET);
        assertThat(cfg.quietOr(false)).isFalse();
    }

    @Test
    void flags_before_the_boundary_still_apply() {
        Jk.applyCliOverrides(new String[] {"-q", "-C", "app", "run", ".", "--", "--verbose"});

        var cfg = SessionContext.current().config();
        assertThat(cfg.quietOr(false)).isTrue();
        assertThat(cfg.verboseOr(false)).isFalse();
        assertThat(cfg.dirOr(UNSET)).isEqualTo(WorkingDirectory.resolve("app"));
    }

    @Test
    void config_switches_are_read_only_within_jks_own_args() {
        var past = Jk.ConfigSwitches.scan(new String[] {"run", ".", "--", "--no-config", "--config-file", "x.toml"});
        assertThat(past.noConfig()).isFalse();
        assertThat(past.explicit()).isEmpty();

        var maven = Jk.ConfigSwitches.scan(new String[] {"mvn", "--no-config"});
        assertThat(maven.noConfig()).isFalse();

        var own = Jk.ConfigSwitches.scan(new String[] {"--config-file=own.toml", "--no-config", "build"});
        assertThat(own.noConfig()).isTrue();
        assertThat(own.explicit()).contains(WorkingDirectory.resolve("own.toml"));
    }

    @Test
    void config_switches_are_read_in_every_spelling_the_parser_takes() {
        var shortForms = Jk.ConfigSwitches.scan(new String[] {"-H", "-D", "own.toml", "-C", "app", "build"});
        assertThat(shortForms.noConfig()).isTrue();
        assertThat(shortForms.explicit()).contains(WorkingDirectory.resolve("own.toml"));
        assertThat(shortForms.dir()).contains(WorkingDirectory.resolve("app"));

        var bundled = Jk.ConfigSwitches.scan(new String[] {"-qHC", "app", "build"});
        assertThat(bundled.noConfig()).isTrue();
        assertThat(bundled.dir()).contains(WorkingDirectory.resolve("app"));

        var after = Jk.ConfigSwitches.scan(new String[] {"build", "-D", "own.toml", "--dir", "app"});
        assertThat(after.explicit()).contains(WorkingDirectory.resolve("own.toml"));
        assertThat(after.dir()).contains(WorkingDirectory.resolve("app"));
    }

    /** {@code jk -C app build} reads {@code app}'s {@code [config]}, not the one where the shell stands. */
    @Test
    void the_project_config_is_read_from_the_directory_dir_names(@TempDir Path tmp) throws Exception {
        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("jk.toml"), "name = \"app\"\n\n[config]\noffline = true\n");
        boolean offline = SessionContext.invocation(() -> {
            Jk.loadAndInstallConfig(new String[] {"-C", app.toString(), "build"});
            return SessionContext.current().config().offlineOr(false);
        });
        assertThat(offline).isTrue();
    }
}
