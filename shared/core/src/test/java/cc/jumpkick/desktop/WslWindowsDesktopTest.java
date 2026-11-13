// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WslWindowsDesktopTest {

    @TempDir
    Path mnt;

    /** A Windows profile under {@code mnt}, reached the way {@code wslpath} would map it. */
    private final class Profile implements WslWindowsDesktop.Shell {
        final List<List<String>> ran = new ArrayList<>();
        int folderReads;

        @Override
        public @Nullable String output(List<String> argv) {
            if (argv.getFirst().equals("wslpath")) {
                return mnt.resolve(argv.getLast().replace("C:\\", "").replace('\\', '/'))
                        .toString();
            }
            folderReads++;
            return "C:\\Users\\me\\AppData\\Roaming\r\nC:\\Users\\me\\AppData\\Local\r\nC:\\Users\\me\r\nC:\\WINDOWS\r\n";
        }

        @Override
        public boolean run(List<String> argv) {
            ran.add(argv);
            return true;
        }
    }

    private Path shortcut() {
        return mnt.resolve("Users/me/AppData/Roaming/Microsoft/Windows/Start Menu/Programs/JumpKick.lnk");
    }

    @Test
    void a_profile_without_jumpkick_gets_the_icon_and_a_shortcut_once() {
        Profile profile = new Profile();
        WslWindowsDesktop desktop = new WslWindowsDesktop(profile);

        desktop.ensure("http://127.0.0.1:8988/");
        desktop.ensure("http://127.0.0.1:8988/");

        assertThat(mnt.resolve("Users/me/AppData/Local/JumpKick/jumpkick.ico")).isRegularFile();
        assertThat(profile.folderReads).as("once per process").isEqualTo(1);
        assertThat(profile.ran).singleElement().satisfies(argv -> assertThat(argv.getFirst())
                .isEqualTo("powershell.exe"));
    }

    @Test
    void powershell_runs_from_where_windows_keeps_it_whatever_the_path() throws Exception {
        WslWindowsDesktop desktop = new WslWindowsDesktop(new Profile());
        List<String> argv = List.of("powershell.exe", "-NoProfile", "-EncodedCommand", "x");

        assertThat(new WslWindowsDesktop(new Profile()).command(argv)).isEqualTo(argv);

        Path exe = mnt.resolve("Windows/System32/WindowsPowerShell/v1.0/powershell.exe");
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "");
        assertThat(desktop.command(argv)).containsExactly(exe.toString(), "-NoProfile", "-EncodedCommand", "x");
        assertThat(desktop.command(List.of("wslpath", "-u", "C:\\"))).containsExactly("wslpath", "-u", "C:\\");
    }

    @Test
    void a_windows_install_keeps_its_own_shortcut() throws Exception {
        Files.createDirectories(shortcut().getParent());
        Files.writeString(shortcut(), "lnk");
        Profile profile = new Profile();

        new WslWindowsDesktop(profile).ensure("http://127.0.0.1:8988/");

        assertThat(profile.ran).isEmpty();
        assertThat(mnt.resolve("Users/me/AppData/Local/JumpKick/jumpkick.ico")).doesNotExist();
    }
}
