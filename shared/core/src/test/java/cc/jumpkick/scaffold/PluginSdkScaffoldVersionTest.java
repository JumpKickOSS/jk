// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code jk new --plugin} writes a hardcoded jk-plugin-sdk version; this tripwire keeps
 * the renderer pin, {@code PluginSdkVersion.VERSION}, and the plugin-sdk Gradle version in sync.
 */
class PluginSdkScaffoldVersionTest {

    @Test
    void scaffolded_sdk_version_matches_the_published_sdk() throws Exception {
        Path repo = findRepoRoot();
        assumeTrue(repo != null, "not running inside the jk repo");

        String sdkSource = Files.readString(
                repo.resolve("shared/plugin-sdk/src/main/java/cc/jumpkick/plugin/PluginSdkVersion.java"));
        Matcher m = Pattern.compile("VERSION = \"([^\"]+)\"").matcher(sdkSource);
        assertThat(m.find()).as("PluginSdkVersion.VERSION literal").isTrue();
        String sdk = m.group(1);

        String gradle = Files.readString(repo.resolve("shared/plugin-sdk/build.gradle.kts"));
        assertThat(gradle)
                .as("plugin-sdk artifact version mirrors PluginSdkVersion.VERSION")
                .contains("version = \"" + sdk + "\"");

        NewInputs plugin = new NewInputs(
                "com.example",
                "my-plugin",
                "temurin-25",
                25,
                25,
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                true,
                NewInputs.Language.JAVA,
                "simple",
                Optional.empty(),
                List.of(),
                false,
                Path.of("."));
        assertThat(NewJkBuildRenderer.render(plugin))
                .as("scaffolded jk-plugin-sdk pin matches the published SDK version")
                .contains("jk-plugin-sdk = { group = \"cc.jumpkick\", version = \"" + sdk + "\" }");
    }

    private static Path findRepoRoot() {
        try {
            Path candidate = Path.of(PluginSdkScaffoldVersionTest.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
            for (int i = 0; i < 12 && candidate != null; i++) {
                if (Files.isRegularFile(candidate.resolve("jk.toml"))
                        && Files.isDirectory(candidate.resolve("shared/plugin-sdk"))) {
                    return candidate;
                }
                candidate = candidate.getParent();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }
}
