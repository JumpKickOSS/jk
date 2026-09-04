// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorShellCheckTest {

    @Test
    void empty_live_set_is_ok_not_a_guess() {
        var check = DoctorCommand.checkShell(Path.of("/tmp"), List.of());
        assertThat(check.status()).isEqualTo(DoctorCommand.Status.OK);
        assertThat(check.detail()).contains("no login or current shell");
    }

    @Test
    void warns_when_login_shell_rc_has_no_block(@TempDir Path home) {
        var check = DoctorCommand.checkShell(home, List.of(new ZshShell()));
        assertThat(check.status()).isEqualTo(DoctorCommand.Status.WARN);
        assertThat(check.detail()).contains("~/.zshrc");
        assertThat(check.detail()).contains("jk activate");
        assertThat(check.detail()).doesNotContain(".bashrc");
    }

    @Test
    void ok_when_login_shell_rc_has_the_block(@TempDir Path home) throws Exception {
        Path rc = home.resolve(".zshrc");
        Files.writeString(rc, ShellInstallerBlock.render(new ZshShell(), home.resolve(".jk/bin"), home) + "\n");
        var check = DoctorCommand.checkShell(home, List.of(new ZshShell()));
        assertThat(check.status()).isEqualTo(DoctorCommand.Status.OK);
        assertThat(check.detail()).isEqualTo("hooks in ~/.zshrc");
    }

    @Test
    void warns_only_the_unhooked_of_login_plus_current(@TempDir Path home) throws Exception {
        Files.writeString(
                home.resolve(".zshrc"),
                ShellInstallerBlock.render(new ZshShell(), home.resolve(".jk/bin"), home) + "\n");
        var check = DoctorCommand.checkShell(home, List.of(new ZshShell(), new FishShell()));
        assertThat(check.status()).isEqualTo(DoctorCommand.Status.WARN);
        assertThat(check.detail()).contains("hooks in ~/.zshrc");
        assertThat(check.detail()).contains("config.fish");
        assertThat(check.detail()).doesNotContain(".bashrc");
    }

    @Test
    void unused_bashrc_is_not_in_the_live_set() {
        assertThat(Shell.live("/bin/zsh", "/bin/zsh", "/usr/bin/zsh").stream()
                        .map(Shell::name)
                        .toList())
                .containsExactly("zsh");
    }

    @Test
    void parent_fish_is_checked_alongside_login_zsh() {
        assertThat(Shell.live("/bin/zsh", null, "/usr/bin/fish").stream()
                        .map(Shell::name)
                        .toList())
                .containsExactly("zsh", "fish");
    }

    @Test
    void parent_sh_is_not_treated_as_bash() {
        assertThat(Shell.live("/bin/zsh", null, "/bin/sh").stream()
                        .map(Shell::name)
                        .toList())
                .containsExactly("zsh");
        assertThat(Shell.live(null, null, "/bin/dash")).isEmpty();
    }

    @Test
    void login_sh_still_maps_to_bash() {
        assertThat(Shell.live("/bin/sh", null, null).stream().map(Shell::name).toList())
                .containsExactly("bash");
    }
}
