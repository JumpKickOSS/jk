// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bundled plugins a named one needs: through plugin ids, aliases, modules another plugin ships,
 * the platform's own modules and required content modules — never through optional ones.
 */
class PluginClosureTest {

    @Test
    void every_required_edge_is_followed_and_no_optional_one(@TempDir Path tmp) throws Exception {
        Path ide = new FakeIde(tmp.resolve("ide"))
                .plugin("com.intellij.java", "java", """
                        <idea-plugin>
                          <id>com.intellij.java</id>
                          <module value="com.intellij.modules.java"/>
                          <depends>com.intellij.modules.lang</depends>
                          <depends optional="true" config-file="kotlin.xml">org.jetbrains.kotlin</depends>
                          <dependencies>
                            <plugin id="intellij.todo.plugin"/>
                          </dependencies>
                          <content>
                            <module name="intellij.java.impl" loading="embedded"><![CDATA[<idea-plugin>
                              <dependencies><module name="intellij.spellchecker"/></dependencies>
                            </idea-plugin>]]></module>
                            <module name="intellij.java.structureView" required-if-available="intellij.platform.backend"/>
                            <module name="intellij.java.featuresTrainer" loading="optional"/>
                          </content>
                        </idea-plugin>
                        """)
                .module("intellij.java.structureView", "moduleV2", "plugins/java/lib/modules/structure.jar", """
                        <idea-plugin><dependencies><module name="intellij.platform.structureView"/></dependencies></idea-plugin>
                        """)
                .module("intellij.java.featuresTrainer", "moduleV2", "plugins/java/lib/modules/trainer.jar", """
                        <idea-plugin><dependencies><plugin id="training"/></dependencies></idea-plugin>
                        """)
                .module("intellij.spellchecker", "productModuleV2", "lib/spellchecker.jar", """
                        <idea-plugin><dependencies><module name="intellij.libraries.lucene"/></dependencies></idea-plugin>
                        """)
                .plugin("intellij.todo.plugin", "todo", "<idea-plugin><id>intellij.todo.plugin</id></idea-plugin>")
                .plugin("intellij.structureView.plugin", "structureView", "<idea-plugin/>")
                .module(
                        "intellij.platform.structureView",
                        "moduleV2",
                        "plugins/structureView/lib/modules/sv.jar",
                        "<idea-plugin/>")
                .plugin("intellij.libraries.misc", "misc", "<idea-plugin/>")
                .module(
                        "intellij.libraries.lucene",
                        "moduleV2",
                        "plugins/misc/lib/modules/lucene.jar",
                        "<idea-plugin/>")
                .plugin("org.jetbrains.kotlin", "Kotlin", "<idea-plugin/>")
                .plugin("training", "featuresTrainer", "<idea-plugin/>")
                .plugin("com.intellij.uses.alias", "aliased", """
                        <idea-plugin><depends>com.intellij.modules.java</depends></idea-plugin>
                        """)
                .write();
        ProductInfo info = ProductInfo.read(ide, ProductInfo.hostOs(), ProductInfo.hostArch());

        assertThat(PluginClosure.of(ide, info, List.of("com.intellij.java")))
                .containsExactlyInAnyOrder(
                        "com.intellij.java",
                        "intellij.todo.plugin",
                        "intellij.structureView.plugin",
                        "intellij.libraries.misc");
        assertThat(PluginClosure.of(ide, info, List.of("com.intellij.uses.alias")))
                .as("an alias is the plugin that declares it")
                .startsWith("com.intellij.uses.alias", "com.intellij.java");
    }
}
