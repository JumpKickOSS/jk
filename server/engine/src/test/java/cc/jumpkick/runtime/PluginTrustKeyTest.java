// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.tool.TrustedPlugins;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a third-party plugin must be trusted as before its worker forks. */
class PluginTrustKeyTest {

    private static final String HEX = "0123456789abcdef".repeat(4);

    private static final PluginDeclaration PATH_PIN = new PluginDeclaration(
            "hello", PluginDeclaration.PATH_GROUP, "hello", "local", "tools/hello.jar", HEX, Map.of());

    private static final PluginDeclaration MAVEN_PIN =
            new PluginDeclaration("hello", "com.example", "hello-jk-plugin", "1.0.0", HEX);

    @Test
    void a_path_pin_is_keyed_by_the_bytes_its_row_carries_and_a_maven_pin_by_its_coordinate() {
        assertThat(PluginBuild.trustKey(PATH_PIN)).isEqualTo("sha256:" + HEX);
        assertThat(PluginBuild.trustKey(MAVEN_PIN)).isEqualTo("com.example:hello-jk-plugin");
    }

    @Test
    void the_alias_of_a_path_pin_trusts_nothing_and_its_digest_trusts_exactly_those_bytes(@TempDir Path state)
            throws Exception {
        TrustedPlugins trust = TrustedPlugins.load(state);
        trust.add("path:hello");
        assertThat(trust.isTrusted(PluginBuild.trustKey(PATH_PIN))).isFalse();
        trust.add("sha256:" + HEX);
        assertThat(trust.isTrusted(PluginBuild.trustKey(PATH_PIN))).isTrue();
        PluginDeclaration swapped = new PluginDeclaration(
                "hello", PluginDeclaration.PATH_GROUP, "hello", "local", "tools/hello.jar", "f".repeat(64), Map.of());
        assertThat(trust.isTrusted(PluginBuild.trustKey(swapped)))
                .as("a different jar behind the same alias asks for trust again")
                .isFalse();
    }

    @Test
    void the_refusal_names_the_pin_and_the_one_command_that_trusts_it() {
        assertThat(PluginBuild.trustRefusal(PATH_PIN))
                .contains("plugin hello (the jar pinned by path, sha256 " + HEX + ")")
                .contains("jk trust plugin sha256:" + HEX)
                .doesNotContain("path:hello");
        assertThat(PluginBuild.trustRefusal(MAVEN_PIN))
                .contains("plugin com.example:hello-jk-plugin:1.0.0")
                .contains("jk trust plugin com.example:hello-jk-plugin");
    }
}
