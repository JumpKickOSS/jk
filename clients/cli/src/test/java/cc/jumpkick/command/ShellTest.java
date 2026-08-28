// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShellTest {

    @Test
    void resolves_by_name_case_insensitive() {
        assertThat(Shell.byName("bash")).get().isInstanceOf(BashShell.class);
        assertThat(Shell.byName("ZSH")).get().isInstanceOf(ZshShell.class);
        assertThat(Shell.byName("Fish")).get().isInstanceOf(FishShell.class);
        assertThat(Shell.byName("pwsh")).get().isInstanceOf(PwshShell.class);
        assertThat(Shell.byName("powershell")).get().isInstanceOf(PwshShell.class);
        assertThat(Shell.byName("sh")).get().isInstanceOf(BashShell.class);
    }

    @Test
    void unknown_shell_is_empty() {
        assertThat(Shell.byName("xonsh")).isEmpty();
        assertThat(Shell.byName(null)).isEmpty();
    }

    @Test
    void bash_set_unset_env() {
        var sh = new BashShell();
        assertThat(sh.setEnv("FOO", "bar")).isEqualTo("export FOO=bar\n");
        assertThat(sh.setEnv("PATH", "/x/y:/z")).isEqualTo("export PATH=/x/y:/z\n"); // path-y chars don't need quoting
        assertThat(sh.setEnv("MSG", "hello world")).isEqualTo("export MSG='hello world'\n");
        assertThat(sh.unsetEnv("FOO")).isEqualTo("unset FOO\n");
    }

    @Test
    void bash_escapes_embedded_single_quotes() {
        assertThat(new BashShell().setEnv("X", "it's")).isEqualTo("export X='it'\\''s'\n");
    }

    @Test
    void zsh_set_unset_env() {
        var sh = new ZshShell();
        assertThat(sh.setEnv("JAVA_HOME", "/home/u/.local/share/jk/jdks/temurin-25"))
                .isEqualTo("export JAVA_HOME=/home/u/.local/share/jk/jdks/temurin-25\n");
        assertThat(sh.unsetEnv("JAVA_HOME")).isEqualTo("unset JAVA_HOME\n");
    }

    @Test
    void fish_set_unset_env() {
        var sh = new FishShell();
        assertThat(sh.setEnv("JAVA_HOME", "/home/u/.local/share/jk/jdks/temurin-25"))
                .isEqualTo("set -gx JAVA_HOME /home/u/.local/share/jk/jdks/temurin-25\n");
        assertThat(sh.unsetEnv("FOO")).isEqualTo("set -e FOO\n");
    }

    @Test
    void pwsh_set_unset_env() {
        var sh = new PwshShell();
        assertThat(sh.setEnv("JAVA_HOME", "/home/u/.local/share/jk/jdks/temurin-25"))
                .isEqualTo("$Env:JAVA_HOME = '/home/u/.local/share/jk/jdks/temurin-25'\n");
        assertThat(sh.unsetEnv("FOO")).isEqualTo("Remove-Item -ErrorAction SilentlyContinue -Path Env:/FOO\n");
    }

    @Test
    void pwsh_escapes_single_quotes_via_doubling() {
        assertThat(new PwshShell().setEnv("X", "it's")).isEqualTo("$Env:X = 'it''s'\n");
    }

    @Test
    void activation_script_includes_exe_path() {
        var out = new ZshShell().activateScript("/home/u/.local/bin/jk");
        assertThat(out).contains("__JK_EXE=/home/u/.local/bin/jk");
        // precmd + chpwd hooks are essential to the contract.
        assertThat(out).contains("add-zsh-hook precmd");
        assertThat(out).contains("add-zsh-hook chpwd");
    }

    @Test
    void bash_activation_uses_prompt_command() {
        var out = new BashShell().activateScript("/home/u/.local/bin/jk");
        assertThat(out).contains("PROMPT_COMMAND");
        assertThat(out).contains("hook-env -s bash");
    }

    @Test
    void fish_activation_uses_pwd_hook() {
        var out = new FishShell().activateScript("/home/u/.local/bin/jk");
        assertThat(out).contains("--on-variable PWD");
        assertThat(out).contains("--on-event fish_prompt");
        assertThat(out).contains("hook-env -s fish");
    }

    @Test
    void pwsh_activation_uses_chpwd_and_prompt() {
        var out = new PwshShell().activateScript("/home/u/.local/bin/jk");
        assertThat(out).contains("LocationChangedAction");
        assertThat(out).contains("global:prompt");
        assertThat(out).contains("hook-env -s pwsh");
    }

    @Test
    void activate_scripts_do_not_define_jk_or_jkx_wrappers() {
        // Real binaries live on PATH; hooks call __JK_EXE, not a shell function.
        for (Shell sh : List.of(new BashShell(), new ZshShell(), new FishShell(), new PwshShell())) {
            String out = sh.activateScript("/home/u/.local/bin/jk");
            assertThat(out).doesNotContain("jkx()");
            assertThat(out).doesNotContain("function jkx");
            assertThat(out).doesNotContain("function global:jkx");
            assertThat(out).doesNotContain("function global:jk");
            assertThat(out).doesNotContain("jk() {");
            assertThat(out).doesNotContain("function jk");
        }
    }

    @Test
    void full_activate_script_includes_path_hooks_and_completions() {
        Path home = Path.of("/home/u");
        Path bin = home.resolve(".local/bin");
        Path data = home.resolve(".local/share/jk");
        String out = new ZshShell().fullActivateScript("/home/u/.local/bin/jk", bin, data, home);
        assertThat(out).contains("$HOME/.local/bin");
        assertThat(out).contains("hook-env -s zsh");
        assertThat(out).contains("$HOME/.local/share/jk/completions/zsh");
        assertThat(out).contains("add-zsh-hook");
    }

    @Test
    void zsh_rc_file_is_dot_zshrc_not_zshenv() {
        // .zshrc is the interactive-shell config; .zshenv runs even for
        // non-interactive subshells where our precmd/chpwd hooks don't fit.
        var home = Path.of("/home/u");
        assertThat(new ZshShell().rcFile(home)).isEqualTo(home.resolve(".zshrc"));
        assertThat(new ZshShell().rcFileDisplay()).isEqualTo("~/.zshrc");
    }

    @Test
    void bash_rc_file_is_dot_bashrc() {
        var home = Path.of("/home/u");
        assertThat(new BashShell().rcFile(home)).isEqualTo(home.resolve(".bashrc"));
        assertThat(new BashShell().rcFileDisplay()).isEqualTo("~/.bashrc");
    }

    @Test
    void fish_rc_file_is_config_fish() {
        var home = Path.of("/home/u");
        assertThat(new FishShell().rcFile(home))
                .isEqualTo(home.resolve(".config").resolve("fish").resolve("config.fish"));
    }

    @Test
    void activation_lines_use_home_relative_command() {
        Path home = Path.of("/home/u");
        Path jk = home.resolve(".local/bin/jk");
        assertThat(new BashShell().activationLine(new BashShell().commandExpr(jk, home)))
                .isEqualTo("eval \"$(\"$HOME/.local/bin/jk\" activate bash)\"");
        assertThat(new ZshShell().activationLine(new ZshShell().commandExpr(jk, home)))
                .isEqualTo("eval \"$(\"$HOME/.local/bin/jk\" activate zsh)\"");
        assertThat(new FishShell().activationLine(new FishShell().commandExpr(jk, home)))
                .isEqualTo("\"$HOME/.local/bin/jk\" activate fish | source");
        assertThat(new PwshShell().activationLine(new PwshShell().commandExpr(jk, home)))
                .isEqualTo("& \"$HOME/.local/bin/jk\" activate pwsh | Out-String | Invoke-Expression");
    }

    @Test
    void activation_lines_use_absolute_command_outside_home() {
        Path home = Path.of("/home/u");
        // /tmp is an allowed non-home root on POSIX; still must stay absolute, not $HOME-relative.
        Path jk = Path.of("/tmp/jk/bin/jk");
        String jkSlash = jk.toAbsolutePath().normalize().toString().replace('\\', '/');
        assertThat(new ZshShell().activationLine(new ZshShell().commandExpr(jk, home)))
                .isEqualTo("eval \"$(\"" + jkSlash + "\" activate zsh)\"");
    }

    @Test
    void installer_block_is_one_line_with_home_relative_path() {
        Path home = Path.of("/home/u");
        String block = ShellInstallerBlock.render(new ZshShell(), home.resolve(".local/bin"), home);
        assertThat(block).contains(ShellInstallerBlock.BEGIN).contains(ShellInstallerBlock.END);
        assertThat(block).contains(ShellInstallerBlock.COMMENT);
        assertThat(block).contains("eval \"$(\"$HOME/.local/bin/jk\" activate zsh)\"");
        assertThat(block).doesNotContain("/home/u");
        assertThat(block).doesNotContain("fpath=");
        assertThat(block).doesNotContain("case \":$PATH:\"");
    }

    @Test
    void installer_block_upsert_is_idempotent() {
        String block = ShellInstallerBlock.render(new BashShell(), Path.of("/home/u/.local/bin"), Path.of("/home/u"));
        String once = ShellInstallerBlock.upsert("", block);
        String twice = ShellInstallerBlock.upsert(once, block);
        assertThat(twice.split(ShellInstallerBlock.BEGIN, -1)).hasSize(2);
    }

    @Test
    void shell_path_expr_prefers_home() {
        Path home = Path.of("/home/u");
        assertThat(ShellPathExpr.posix(home.resolve(".local/bin"), home)).isEqualTo("$HOME/.local/bin");
        String outsideSlash = Path.of("/tmp/elsewhere/bin")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/');
        assertThat(ShellPathExpr.posix(Path.of("/tmp/elsewhere/bin"), home)).isEqualTo(outsideSlash);
        assertThat(ShellPathExpr.posixCommand(home.resolve(".local/bin/jk"), home))
                .isEqualTo("\"$HOME/.local/bin/jk\"");
    }

    @Test
    void detect_resolves_shell_from_path_basename() {
        assertThat(Shell.detect("/home/u/.local/bin/zsh")).get().isInstanceOf(ZshShell.class);
        assertThat(Shell.detect("/home/u/.local/bin/fish")).get().isInstanceOf(FishShell.class);
        assertThat(Shell.detect("bash")).get().isInstanceOf(BashShell.class);
        assertThat(Shell.detect("/home/u/.local/bin/dash")).isEmpty();
        assertThat(Shell.detect(null)).isEmpty();
        assertThat(Shell.detect("")).isEmpty();
    }
}
