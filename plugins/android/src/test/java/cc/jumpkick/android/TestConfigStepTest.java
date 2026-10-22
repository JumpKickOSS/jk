// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
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
        FakeBuildIo exec = step(tmp);
        Path resStep = exec.step("android-res");

        TestConfigStep.run(exec);

        Properties props = written(exec);
        assertThat(props)
                .containsEntry(
                        "android_merged_manifest",
                        exec.scratch()
                                .resolve("manifest/AndroidManifest.xml")
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
        assertThat(exec.labels()).contains("test_config.properties for com.example.app");
    }

    /** No {@code assets/} in the module means no assets key — Robolectric treats "" as a real dir. */
    @Test
    void a_module_without_assets_writes_no_assets_key(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = step(tmp);
        exec.step("android-res");

        TestConfigStep.run(exec);

        assertThat(written(exec)).doesNotContainKey("android_merged_assets");
    }

    /** With an {@code assets/} dir present (either layout), the key points straight at it. */
    @Test
    void a_modules_assets_dir_is_named_when_present(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = step(tmp);
        exec.step("android-res");
        Path assets = Files.createDirectories(exec.moduleDir().resolve("assets"));

        TestConfigStep.run(exec);

        assertThat(written(exec))
                .containsEntry("android_merged_assets", assets.toAbsolutePath().toString());
    }

    /**
     * The unit-test manifest merges every AAR on the test runtime closure as an application: a
     * test-scope AAR's {@code <activity>} reaches the manifest Robolectric resolves intents against.
     */
    @Test
    void the_unit_test_manifest_merges_the_test_closures_aar_manifests(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = step(tmp).config("library", true);
        exec.step("android-res");
        Files.writeString(exec.moduleDir().resolve("AndroidManifest.xml"), "<manifest><!-- module --></manifest>\n");
        Path testManifest = exec.container("ui-test-manifest").resolve("AndroidManifest.xml");
        Files.writeString(testManifest, """
                <manifest package="androidx.compose.ui.test.manifest">
                  <application><activity android:name="androidx.activity.ComponentActivity"/></application>
                </manifest>
                """);
        exec.container("no-manifest");

        TestConfigStep.run(exec);

        Path merged = Path.of(written(exec).getProperty("android_merged_manifest"));
        assertThat(Files.readString(merged))
                .contains("<!-- module -->")
                .contains("androidx.activity.ComponentActivity")
                .as("an application merge even for a library: placeholders resolve against the namespace")
                .doesNotContain("--merge-type")
                .contains("PACKAGE=com.example.app");
    }

    /** A step fixture whose {@code manifest-merger} is {@link #FAKE_MERGER}, compiled into a jar. */
    private static FakeBuildIo step(Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("merger-src/com/android/manifmerger"));
        Files.writeString(src.resolve("Merger.java"), FAKE_MERGER);
        Path classes = Files.createDirectories(tmp.resolve("merger-classes"));
        int exit = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        null,
                        null,
                        "-d",
                        classes.toString(),
                        src.resolve("Merger.java").toString());
        assertThat(exit).isZero();
        Path lib = Files.createDirectories(tmp.resolve("merger-lib"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(lib.resolve("merger.jar")))) {
            jar.putNextEntry(new JarEntry("com/android/manifmerger/Merger.class"));
            jar.write(Files.readAllBytes(classes.resolve("com/android/manifmerger/Merger.class")));
            jar.closeEntry();
        }
        FakeBuildIo exec = AndroidIo.step(Files.createDirectories(tmp.resolve("module")));
        Files.writeString(exec.moduleDir().resolve("AndroidManifest.xml"), "<manifest/>\n");
        return exec.extra("manifest-merger", lib);
    }

    /** Writes the main manifest, every {@code --libs} manifest and the rest of its argv to {@code --out}. */
    private static final String FAKE_MERGER = """
            package com.android.manifmerger;

            import java.io.File;
            import java.nio.file.Files;
            import java.nio.file.Path;

            public class Merger {
                public static void main(String[] args) throws Exception {
                    StringBuilder out = new StringBuilder();
                    Path target = null;
                    for (int i = 0; i < args.length; i++) {
                        switch (args[i]) {
                            case "--main" -> out.append(Files.readString(Path.of(args[++i])));
                            case "--libs" -> {
                                for (String lib : args[++i].split(File.pathSeparator)) {
                                    out.append(Files.readString(Path.of(lib)));
                                }
                            }
                            case "--out" -> target = Path.of(args[++i]);
                            default -> out.append(args[i]).append('\\n');
                        }
                    }
                    Files.writeString(target, out);
                }
            }
            """;

    /** Read back the way Robolectric reads it — through {@link Properties}. */
    private static Properties written(FakeBuildIo exec) throws Exception {
        Path file = exec.scratch().resolve("cp/com/android/tools/test_config.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        return props;
    }
}
