// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Robolectric's {@code test_config.properties}, written onto the test runtime classpath at the
 * exact resource path Robolectric probes ({@code com/android/tools/}). The keys are Robolectric's
 * vocabulary, not jk's: a misspelled one is silently ignored and the tests run against an empty
 * app instead of failing.
 */
class TestConfigStepTest {

    /** Each key points into the manifest/res step outputs; the package is the config namespace. */
    @Test
    void the_properties_name_the_merged_manifest_resources_apk_and_package(@TempDir Path tmp) throws Exception {
        FakeTaskExec exec = new FakeTaskExec(tmp);
        Path manifestStep = exec.step("android-manifest");
        Path resStep = exec.step("android-res");

        TestConfigStep.run(exec);

        Properties props = written(exec);
        assertThat(props)
                .containsEntry(
                        "android_merged_manifest",
                        manifestStep
                                .resolve("merged/AndroidManifest.xml")
                                .toAbsolutePath()
                                .toString());
        assertThat(props)
                .containsEntry(
                        "android_merged_resources",
                        resStep.resolve("raw-res").toAbsolutePath().toString());
        assertThat(props)
                .containsEntry(
                        "android_resource_apk",
                        resStep.resolve("packaged/resources.ap_")
                                .toAbsolutePath()
                                .toString());
        assertThat(props).containsEntry("android_custom_package", "com.example.app");
        assertThat(exec.labels).contains("test_config.properties for com.example.app");
    }

    /** No {@code assets/} in the module means no assets key — Robolectric treats "" as a real dir. */
    @Test
    void a_module_without_assets_writes_no_assets_key(@TempDir Path tmp) throws Exception {
        FakeTaskExec exec = new FakeTaskExec(tmp);
        exec.step("android-manifest");
        exec.step("android-res");

        TestConfigStep.run(exec);

        assertThat(written(exec)).doesNotContainKey("android_merged_assets");
    }

    /** With an {@code assets/} dir present (either layout), the key points straight at it. */
    @Test
    void a_modules_assets_dir_is_named_when_present(@TempDir Path tmp) throws Exception {
        FakeTaskExec exec = new FakeTaskExec(tmp);
        exec.step("android-manifest");
        exec.step("android-res");
        Path assets = Files.createDirectories(exec.moduleDir().resolve("assets"));

        TestConfigStep.run(exec);

        assertThat(written(exec))
                .containsEntry("android_merged_assets", assets.toAbsolutePath().toString());
    }

    /** Read back the way Robolectric reads it — through {@link Properties}. */
    private static Properties written(FakeTaskExec exec) throws Exception {
        Path file = exec.scratch().resolve("cp/com/android/tools/test_config.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        return props;
    }
}
