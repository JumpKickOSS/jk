// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        assertThat(cfg.directoryOr(UNSET)).isEqualTo(UNSET);
    }

    @Test
    void flags_after_a_passthrough_command_leave_the_session_untouched() {
        Jk.applyCliOverrides(new String[] {"mvn", "-C", "install", "-q"});

        var cfg = SessionContext.current().config();
        assertThat(cfg.directoryOr(UNSET)).isEqualTo(UNSET);
        assertThat(cfg.quietOr(false)).isFalse();
    }

    @Test
    void flags_before_the_boundary_still_apply() {
        Jk.applyCliOverrides(new String[] {"-q", "-C", "app", "run", ".", "--", "--verbose"});

        var cfg = SessionContext.current().config();
        assertThat(cfg.quietOr(false)).isTrue();
        assertThat(cfg.verboseOr(false)).isFalse();
        assertThat(cfg.directoryOr(UNSET)).isEqualTo(Path.of("app"));
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
        assertThat(own.explicit()).contains(Path.of("own.toml"));
    }
}
