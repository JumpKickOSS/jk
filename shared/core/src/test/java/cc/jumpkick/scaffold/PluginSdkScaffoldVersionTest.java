// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.model.Layout;
import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code jk new --plugin} writes a hardcoded jk-plugin-sdk version; this tripwire holds it against
 * the one owner of that version, the {@code version} line {@code :plugin-sdk} publishes under. A
 * Gradle script cannot read a Java constant, so the renderer's copy cannot be deleted — but it can
 * be checked, and this is the check. (deleted the third copy, {@code
 * PluginSdkVersion.VERSION}: no production code read it.)
 */
class PluginSdkScaffoldVersionTest {

    @Test
    void scaffolded_sdk_version_matches_the_published_sdk() throws Exception {
        Path repo = findRepoRoot();
        assumeTrue(repo != null, "not running inside the jk repo");

        String gradle = Files.readString(repo.resolve("shared/plugin-sdk/build.gradle.kts"));
        Matcher m = Pattern.compile("(?m)^version = \"([^\"]+)\"$").matcher(gradle);
        assertThat(m.find())
                .as("shared/plugin-sdk/build.gradle.kts declares the published SDK version")
                .isTrue();
        String sdk = m.group(1);

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
                .as("scaffolded jk-plugin-sdk pin matches the published SDK version")
                .contains("jk-plugin-sdk = { group = \"cc.jumpkick\", version = \"" + sdk + "\" }");
    }

    private static Path findRepoRoot() {
        return RepoRoot.find(PluginSdkScaffoldVersionTest.class);
    }
}
