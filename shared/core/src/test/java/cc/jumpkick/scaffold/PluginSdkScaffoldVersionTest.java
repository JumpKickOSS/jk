// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.Layout;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code jk new --plugin} pins the SDK a new plugin compiles against at jk's own version: the SDK
 * rides the release train, and {@code jk install} shelves it under that version, so a scaffold
 * pinning anything else resolves an SDK nobody published.
 */
class PluginSdkScaffoldVersionTest {

    @Test
    void scaffolded_sdk_version_is_jks_own() {
        NewInputs plugin = new NewInputs(
                "com.example",
                "my-plugin",
                "temurin-25",
                25,
                25,
                null,
                null,
                false,
                false,
                true,
                NewInputs.Language.JAVA,
                Layout.SIMPLE,
                null,
                List.of(),
                false,
                Path.of("."));
        assertThat(NewJkBuildRenderer.render(plugin))
                .as("scaffolded jk-plugin-sdk pin is the version this jk publishes the SDK under")
                .contains("jk-plugin-sdk = { group = \"cc.jumpkick\", version = \"" + JkVersion.VERSION + "\" }");
    }
}
