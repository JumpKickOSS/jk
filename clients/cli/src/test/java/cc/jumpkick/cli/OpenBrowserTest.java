// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure argv selection for {@link OpenBrowser} — no process is started. */
class OpenBrowserTest {

    @Test
    void prefers_BROWSER_env_over_platform_default() {
        List<String> cmd = OpenBrowser.command(
                "http://example/", k -> "BROWSER".equals(k) ? "/usr/bin/firefox" : null, "Linux");
        assertThat(cmd).containsExactly("/usr/bin/firefox", "http://example/");
    }

    @Test
    void linux_defaults_to_xdg_open() {
        assertThat(OpenBrowser.command("http://x/", k -> null, "Linux")).containsExactly("xdg-open", "http://x/");
    }

    @Test
    void mac_defaults_to_open() {
        assertThat(OpenBrowser.command("http://x/", k -> null, "Mac OS X")).containsExactly("open", "http://x/");
    }

    @Test
    void windows_uses_cmd_start_with_empty_title() {
        List<String> cmd = OpenBrowser.command("http://x/", k -> null, "Windows 11");
        assertThat(cmd).containsExactly("cmd", "/c", "start", "", "http://x/");
    }

    @Test
    void blank_url_yields_empty_command() {
        assertThat(OpenBrowser.command("  ", k -> null, "Linux")).isEmpty();
    }
}
