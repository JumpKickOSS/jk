// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The desktop registration: a desktop entry and PNG on Linux, a Start-menu shortcut and ICO on
 * Windows, nothing on macOS; written for the default home only when it is missing.
 */
class DesktopIdentityTest {

    @TempDir
    Path userHome;

    private final List<List<String>> ran = new ArrayList<>();
    private boolean runnerSucceeds = true;

    private DesktopIdentity on(String os, Map<String, String> env) {
        return new DesktopIdentity(os, JkDirs.of(env::get, userHome.toString(), os), userHome, env::get, argv -> {
            ran.add(argv);
            return runnerSucceeds;
        });
    }

    @Test
    void linux_writes_a_hidden_desktop_entry_naming_the_installed_png_and_removes_it_again() throws IOException {
        DesktopIdentity identity = on("Linux", Map.of());
        Path entry = userHome.resolve(".local/share/applications/jumpkick.desktop");
        assertThat(identity.entry()).isEqualTo(entry);
        assertThat(identity.registered()).isFalse();

        identity.register();

        Path png = userHome.resolve(".jk/lib/desktop/jumpkick.png");
        assertThat(identity.registered()).isTrue();
        assertThat(Files.readAllBytes(png)).startsWith(0x89, 'P', 'N', 'G');
        assertThat(entry)
                .content()
                .startsWith("[Desktop Entry]\n")
                .contains(
                        "Name=JumpKick\n",
                        "Exec=\"" + userHome.resolve(".jk/bin/jk") + "\" web\n",
                        "Icon=" + png + "\n",
                        "NoDisplay=true\n");
        assertThat(ran).isEmpty();
        assertThat(identity.installed()).containsExactly(entry, png.getParent());

        assertThat(identity.unregister()).isTrue();
        assertThat(entry).doesNotExist();
        assertThat(png.getParent()).doesNotExist();
        assertThat(identity.unregister()).isFalse();
    }

    @Test
    void xdg_data_home_moves_the_desktop_entry() {
        Path data = userHome.resolve("data");
        assertThat(on("Linux", Map.of("XDG_DATA_HOME", data.toString())).entry())
                .isEqualTo(data.resolve("applications/jumpkick.desktop"));
        assertThat(on("Linux", Map.of("XDG_DATA_HOME", "relative")).entry())
                .isEqualTo(userHome.resolve(".local/share/applications/jumpkick.desktop"));
    }

    @Test
    void windows_writes_the_ico_and_a_shortcut_carrying_the_app_user_model_id() throws IOException {
        Path roaming = userHome.resolve("Roaming");
        DesktopIdentity identity = on("Windows 11", Map.of("APPDATA", roaming.toString()));
        Path lnk = roaming.resolve("Microsoft/Windows/Start Menu/Programs/JumpKick.lnk");
        assertThat(identity.entry()).isEqualTo(lnk);

        identity.register();

        Path ico = userHome.resolve(".jk/lib/desktop/jumpkick.ico");
        assertThat(Files.readAllBytes(ico)).startsWith(0, 0, 1, 0);
        assertThat(ran).singleElement().satisfies(argv -> {
            assertThat(argv.subList(0, 4))
                    .containsExactly("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand");
            String script = new String(Base64.getDecoder().decode(argv.getLast()), StandardCharsets.UTF_16LE);
            assertThat(script).startsWith(PowerShell.PRELUDE).contains("[JumpKickDesktop.Shortcut]::Save(");
            List<String> values = embedded(script);
            assertThat(values.getFirst()).contains("IPropertyStore", "9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3");
            assertThat(values.subList(1, values.size()))
                    .containsExactly(
                            lnk.toString(),
                            userHome.resolve(".jk/bin/jk.exe").toString(),
                            "web",
                            userHome.toString(),
                            ico.toString(),
                            "Open the JumpKick dashboard",
                            DesktopIdentity.AUMID);
        });
    }

    @Test
    void a_shortcut_powershell_could_not_write_is_an_error() {
        runnerSucceeds = false;
        assertThatThrownBy(() -> on("Windows 11", Map.of()).register())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("JumpKick.lnk");
    }

    @Test
    void macos_has_nothing_to_register() throws IOException {
        DesktopIdentity identity = on("Mac OS X", Map.of());
        assertThat(identity.entry()).isNull();
        assertThat(identity.registered()).isTrue();
        identity.register();
        assertThat(userHome.resolve(".jk")).doesNotExist();
    }

    @Test
    void ensure_registers_the_default_home_once() throws IOException {
        DesktopIdentity identity = on("Linux", Map.of());
        identity.ensure();
        Path entry = identity.entry();
        assertThat(entry).exists();

        Files.delete(entry);
        identity.ensure();
        assertThat(entry).doesNotExist();
    }

    @Test
    void ensure_leaves_the_desktop_alone_for_any_other_home() throws IOException {
        DesktopIdentity identity =
                on("Linux", Map.of("JK_HOME", userHome.resolve("scratch").toString()));
        identity.ensure();
        assertThat(identity.entry()).doesNotExist();
        assertThat(identity.png()).doesNotExist();

        Path entry = userHome.resolve(".local/share/applications/jumpkick.desktop");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "the default home's");
        assertThat(identity.installed())
                .as("the default home's registration is not this home's")
                .isEmpty();
    }

    /** Every base64 literal {@code script} decodes, in order. */
    private static List<String> embedded(String script) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("D '([A-Za-z0-9+/=]*)'").matcher(script);
        while (m.find()) out.add(new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8));
        return out;
    }
}
