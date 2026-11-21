// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import org.junit.jupiter.api.Test;

class NativeDeclaredConditionTest {

    /** {@code native-declared} holds for a module that builds a native image, not for one whose [native] is off. */
    @Test
    void native_declared_follows_whether_the_module_builds_a_native_image() {
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                [plugin]
                id = "nativeroot-fixture"
                table = "nativeroot-fixture"

                [[contribute.source-roots]]
                dir = "native/res"
                kind = "resource"
                when = { native-declared = true }
                """, "nativeroot-fixture.toml"), null);
        String base = """
                name = "demo"
                group = "com.example"
                version = "1.0.0"
                [nativeroot-fixture]
                """;
        assertThat(PluginContributions.sourceRoots(JkBuildParser.parse(base + "[native]\n"), null))
                .extracting(PluginContributions.SourceRoot::dir)
                .containsExactly("native/res");
        assertThat(PluginContributions.sourceRoots(JkBuildParser.parse(base + "[native]\nenabled = false\n"), null))
                .isEmpty();
        assertThat(PluginContributions.sourceRoots(
                        JkBuildParser.parse(base + "[application]\nmain = \"com.example.Main\"\nnative = true\n"),
                        null))
                .extracting(PluginContributions.SourceRoot::dir)
                .containsExactly("native/res");
    }
}
