// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * On Windows a variable's name matches whatever its case: a mixed-case proxy spelling still rides,
 * and a name set compares the way the platform does. The flag is passed, so this runs on any host.
 */
class RequestEnvNamesCaseTest {

    @Test
    void a_mixed_case_proxy_name_is_read_when_names_are_case_insensitive() {
        assertThat(RequestEnvNames.engineReads("HTTP_PROXY", true)).isTrue();
        assertThat(RequestEnvNames.engineReads("Http_Proxy", true)).isTrue();
        assertThat(RequestEnvNames.engineReads("No_Proxy", true)).isTrue();
        assertThat(RequestEnvNames.engineReads("Wayland_Display", true)).isTrue();
    }

    @Test
    void names_are_exact_where_the_platform_is() {
        assertThat(RequestEnvNames.engineReads("Http_Proxy", false)).isFalse();
        assertThat(BuildEnv.nameSet(List.of("MY_TOKEN"), false).contains("My_Token")).isFalse();
        assertThat(BuildEnv.nameSet(List.of("MY_TOKEN"), true).contains("My_Token")).isTrue();
        assertThat(BuildEnv.nameSet(List.of("MY_TOKEN"), true).contains("my_token")).isTrue();
    }
}
