// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Robolectric's {@code test_config.properties}, written onto the test runtime classpath at the
 * exact resource path Robolectric probes ({@code com/android/tools/}). The keys are Robolectric's
 * vocabulary, not jk's: a misspelled one is silently ignored and the tests run against an empty
 * app instead of failing. The stub {@code aapt2} is a shell script, so the class skips Windows.
 */
@DisabledOnOs(OS.WINDOWS)
class TestConfigStepTest {

    /** Each key points into this step's or {@code android-res}'s outputs; the package is the namespace. */
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
                        exec.scratch()
                                .resolve("packaged/resources.ap_")
                                .toAbsolutePath()
                                .toString());
        assertThat(props).containsEntry("android_custom_package", "com.example.app");
        assertThat(exec.labels()).contains("test_config.properties for com.example.app");
    }

    /** Robolectric on the test closure brings the JDK flags it needs; without it the file is empty. */
    @Test
    void robolectric_on_the_test_closure_gets_its_jdk_flags(@TempDir Path tmp) throws Exception {
        FakeBuildIo plain = step(tmp.resolve("plain"));
        plain.step("android-res");
        TestConfigStep.run(plain);
        assertThat(plain.scratch().resolve(TestConfigStep.TEST_JVM_ARGS)).isEmptyFile();

        FakeBuildIo robo = step(tmp.resolve("robo"));
        robo.step("android-res");
        robo.entry("robolectric-4.17.jar", "org.robolectric", "robolectric", "4.17");
        TestConfigStep.run(robo);
        assertThat(Files.readAllLines(robo.scratch().resolve(TestConfigStep.TEST_JVM_ARGS)))
                .containsExactlyElementsOf(TestConfigStep.ROBOLECTRIC_JVM_ARGS);
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

    /**
     * Robolectric's binary-resources mode reads the manifest compiled into the resource package, so
     * the step links its own: the test closure's AAR resources (a test-scope AAR's manifest names
     * its own themes) and the module's, against the unit-test manifest, with the main link's ids.
     */
    @Test
    void the_unit_test_resource_package_links_the_test_closure_against_the_unit_test_manifest(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo exec = step(tmp);
        Path resStep = exec.step("android-res");
        Path ownRes = Files.createDirectories(resStep.resolve("raw-res/values"));
        Files.writeString(ownRes.resolve("strings.xml"), "<resources><string name=\"app\">x</string></resources>");
        Path ids = Files.writeString(
                Files.createDirectories(resStep.resolve("packaged")).resolve("ids.txt"),
                "com.example.app:string/app = 0x7f010000\n");
        Path testAar = exec.container("androidx-test-core");
        Files.writeString(testAar.resolve("AndroidManifest.xml"), """
                <manifest package="androidx.test.core">
                  <application><activity android:name="androidx.activity.ComponentActivity"
                      android:theme="@style/WhiteBackgroundTheme"/></application>
                </manifest>
                """);
        Path testAarValues = Files.createDirectories(testAar.resolve("res/values"));
        Files.writeString(
                testAarValues.resolve("values.xml"),
                "<resources><style name=\"WhiteBackgroundTheme\" parent=\"android:Theme\"/></resources>");

        TestConfigStep.run(exec);

        Properties props = written(exec);
        Path apk = Path.of(props.getProperty("android_resource_apk"));
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            String manifest = new String(
                    zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(manifest).contains("androidx.activity.ComponentActivity");
        }
        List<String> compiled = Files.readAllLines(tmp.resolve("aapt2-compile.txt"));
        assertThat(compiled)
                .hasSize(2)
                .contains(resStep.resolve("raw-res").toAbsolutePath().toString());
        Path mergedDeps = compiled.stream()
                .map(Path::of)
                .filter(dir -> !dir.equals(resStep.resolve("raw-res").toAbsolutePath()))
                .findFirst()
                .orElseThrow();
        try (var files = Files.walk(mergedDeps)) {
            assertThat(files.filter(Files::isRegularFile).map(f -> {
                        try {
                            return Files.readString(f);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }))
                    .as("the test-scope AAR's resources are compiled into the unit-test package")
                    .anyMatch(text -> text.contains("WhiteBackgroundTheme"));
        }
        List<String> argv = Files.readAllLines(tmp.resolve("aapt2-link.txt"));
        assertThat(argv)
                .containsSubsequence("--manifest", props.getProperty("android_merged_manifest"))
                .containsSubsequence("--stable-ids", ids.toAbsolutePath().toString())
                .contains("-R")
                .as("the main link's R.java and symbols stay android-res's")
                .doesNotContain("--java", "--output-text-symbols", "--proguard");
        assertThat(exec.labels()).contains("aapt2 link (unit test)");
    }

    /**
     * A step fixture whose {@code manifest-merger} is {@link #FAKE_MERGER}, compiled into a jar, and
     * whose {@code aapt2} is {@link #fakeAapt2}.
     */
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
        Path platformJar = Files.writeString(tmp.resolve("android.jar"), "");
        return exec.extra("manifest-merger", lib).extra("aapt2", fakeAapt2(tmp)).extra("android-jar", platformJar);
    }

    /**
     * An aapt2 wrapper jar whose binary appends each {@code compile --dir} to {@code
     * aapt2-compile.txt}, records a link's argv to {@code aapt2-link.txt}, and links by packaging the
     * {@code --manifest} file as the {@code -o} archive's {@code AndroidManifest.xml}.
     */
    private static Path fakeAapt2(Path tmp) throws Exception {
        Path jarTool = Path.of(System.getProperty("java.home"), "bin", "jar");
        String script = """
                #!/bin/sh
                cmd="$1"; shift
                [ "$cmd" = link ] && printf '%%s\\n' "$@" > '%1$s/aapt2-link.txt'
                out=; manifest=
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -o) out="$2"; shift ;;
                    --manifest) manifest="$2"; shift ;;
                    --dir) printf '%%s\\n' "$2" >> '%1$s/aapt2-compile.txt'; shift ;;
                  esac
                  shift
                done
                if [ "$cmd" = compile ]; then : > "$out"; exit 0; fi
                stage=$(mktemp -d)
                cp "$manifest" "$stage/AndroidManifest.xml"
                exec '%2$s' cf "$out" -C "$stage" AndroidManifest.xml
                """.formatted(tmp, jarTool);
        Path wrapper = tmp.resolve("aapt2.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(wrapper))) {
            jar.putNextEntry(new JarEntry("aapt2"));
            jar.write(script.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return wrapper;
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
