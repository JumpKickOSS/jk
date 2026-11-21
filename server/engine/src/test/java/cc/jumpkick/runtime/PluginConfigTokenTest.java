// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.PluginConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A plugin's config token reads its values and never its secrets. */
class PluginConfigTokenTest {

    @Test
    void a_secret_never_moves_the_config_token() {
        Map<String, Object> values = Map.of("jdbc-url", "jdbc:postgresql://db/app");
        String plain = PluginBuild.configToken(new PluginConfig("jooq", values));

        assertThat(PluginBuild.configToken(new PluginConfig("jooq", values, Map.of("jdbc-password", "one"))))
                .isEqualTo(plain)
                .isEqualTo(PluginBuild.configToken(new PluginConfig("jooq", values, Map.of("jdbc-password", "two"))));
        assertThat(PluginBuild.configToken(new PluginConfig("jooq", Map.of("jdbc-url", "jdbc:postgresql://db/other"))))
                .isNotEqualTo(plain);
    }
}
