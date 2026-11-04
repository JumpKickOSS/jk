// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [[contribute.lock-closure]]}: a closure the plugin resolves for {@code jk lock} to pin,
 * declared by name. Only an active plugin's closures are asked for, so a module without the
 * plugin's table costs nothing.
 */
class LockClosureContributionTest {

    private static final String HEAD = """
            [plugin]
            id = "hello"
            table = "hello"
            version = "0.1.0"

            """;

    @Test
    void each_closure_is_declared_by_name() {
        PluginDescriptor d = PluginDescriptors.parse(HEAD + """
                [[contribute.lock-closure]]
                name = "deployment"

                [[contribute.lock-closure]]
                name = "codegen-2"
                """, "hello.toml");
        assertThat(d.contributions().lockClosures())
                .extracting(PluginDescriptor.LockClosure::name)
                .containsExactly("deployment", "codegen-2");
        assertThat(d.contributions().isEmpty()).isFalse();
    }

    @Test
    void a_name_is_a_lower_case_word_declared_once() {
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [[contribute.lock-closure]]
                        name = "Deployment Closure"
                        """, "hello.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("hello.toml.contribute.lock-closure.name");
        assertThatThrownBy(() -> PluginDescriptors.parse(HEAD + """
                        [[contribute.lock-closure]]
                        name = "deployment"

                        [[contribute.lock-closure]]
                        name = "deployment"
                        """, "hello.toml"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("`deployment` is declared twice");
    }

    /** The built-in [quarkus] plugin declares its deployment closure; a module without its table asks none. */
    @Test
    void only_an_active_plugin_is_asked(@TempDir Path dir) {
        var quarkus = JkBuildParser.parse("""
                group = "g"
                name = "n"
                version = "1"

                [quarkus]
                version = "3.40.1"
                """);
        assertThat(PluginContributions.lockClosures(quarkus, dir))
                .extracting(PluginContributions.LockClosureDep::resolvedBy)
                .containsExactly("quarkus/deployment");

        var plain = JkBuildParser.parse("""
                group = "g"
                name = "n"
                version = "1"
                """);
        assertThat(PluginContributions.lockClosures(plain, dir)).isEmpty();
    }
}
