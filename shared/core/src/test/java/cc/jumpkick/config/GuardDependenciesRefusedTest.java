// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** No ninth scope: a guard suite compiles against test-dependencies plus the provisioned library. */
class GuardDependenciesRefusedTest {

    @Test
    void a_guard_dependencies_table_is_refused_with_the_alternative() {
        String toml =
                "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njdk = 25\n\n[guard-dependencies]\narchunit = { group = \"com.tngtech.archunit\", version = \"1.3.0\" }\n";
        assertThatThrownBy(() -> JkBuildParser.parse(toml))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[guard-dependencies] is not a scope")
                .hasMessageContaining("[test-dependencies]");
    }
}
