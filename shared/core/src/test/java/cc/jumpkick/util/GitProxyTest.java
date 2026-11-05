// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitProxyTest {

    @Test
    void the_proxy_is_appended_after_the_config_entries_the_environment_already_carries() {
        Map<String, String> env = new HashMap<>(Map.of(
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", "core.askPass",
                "GIT_CONFIG_VALUE_0", "",
                "no_proxy", "github.com",
                "NO_PROXY", "github.com"));

        GitProxy.configure(env, "http://proxy.corp:3128");

        assertThat(env)
                .containsEntry("GIT_CONFIG_COUNT", "2")
                .containsEntry("GIT_CONFIG_KEY_0", "core.askPass")
                .containsEntry("GIT_CONFIG_KEY_1", "http.proxy")
                .containsEntry("GIT_CONFIG_VALUE_1", "http://proxy.corp:3128")
                .as("jk already applied its own bypass list")
                .doesNotContainKeys("no_proxy", "NO_PROXY");
    }

    @Test
    void a_direct_route_is_git_s_empty_proxy_and_leaves_the_bypass_list_alone() {
        Map<String, String> env = new HashMap<>(Map.of("no_proxy", "intranet"));

        GitProxy.configure(env, "");

        assertThat(env)
                .containsEntry("GIT_CONFIG_COUNT", "1")
                .containsEntry("GIT_CONFIG_KEY_0", "http.proxy")
                .containsEntry("GIT_CONFIG_VALUE_0", "")
                .containsEntry("no_proxy", "intranet");
    }

    @Test
    void only_http_remotes_are_asked_about() {
        GitProxy proxy = remote -> Optional.of("http://proxy.corp:3128/" + remote.getHost());

        assertThat(proxy.httpProxy("https://github.com/JumpKickOSS/jk-templates.git"))
                .contains("http://proxy.corp:3128/github.com");
        assertThat(proxy.httpProxy("git@github.com:JumpKickOSS/jk-templates.git"))
                .isEmpty();
        assertThat(proxy.httpProxy("ssh://git@github.com/JumpKickOSS/jk-templates.git"))
                .isEmpty();
        assertThat(proxy.httpProxy("file:///srv/jk-templates.git")).isEmpty();
    }
}
