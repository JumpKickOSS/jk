// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.androidsdk.AndroidRepoFeed;
import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.androidsdk.AndroidSdkInstaller;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.WorkerLaunchClasspath;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.testing.TestCaches;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The P6 validation gate (build-plugins plan §4; android-plan.md Task 1): {@code jk build} on a
 * minimal Android hello-world produces a signed APK via {@code plugins/android}, with the
 * R-gen → compile → dex → assemble chain running entirely over the public SPI — the engine has
 * zero Android-specific code.
 *
 * <p>Real tools, really fetched: aapt2 (per-OS classifier) and r8 from Google Maven, the
 * Step-1 platform stand-in (Maven-published android-all — see the plugin manifest's note) from
 * Central. The CAS lives under the module's build dir, not a @TempDir, so repeat runs are warm
 * (the platform jar is ~115MB once).
 */
@Tag("slow")
@ExtendWith(SysProps.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AndroidSpikeTest {

    /** The built hello-world: its checkout, the CAS it built against, the managed SDK root, the APK. */
    private record Built(Path project, Path cache, Path sdkRoot, Path apk) {}

    // A persistent CAS + SDK root across runs — the platform is a one-time download.
    private static final Path CACHE = TestCaches.dir("android-spike-cache");
    private static final Path SDK_ROOT = TestCaches.dir("android-spike-sdk");

    @TempDir
    Path tmp;

    /** Built once per class, by whichever test asks first; every test reads the same APK. */
    private @Nullable Built built;

    @BeforeEach
    void pointAtTheManagedSdkRoot() {
        // SysProps restores the table around each method, so every test sets the root itself.
        System.setProperty(AndroidSdk.ROOT_PROPERTY, SDK_ROOT.toString());
    }

    private Built built() throws Exception {
        Built b = built;
        if (b == null) {
            b = buildHelloWorld();
            built = b;
        }
        return b;
    }

    @Test
    void jk_build_produces_a_signed_apk_over_the_public_spi() throws Exception {
        Built b = built();
        Path project = b.project();
        // manifest-merger ran: package + <uses-sdk> injected into the merged manifest.
        Path merged = project.resolve("target/plugin/android-manifest/merged/AndroidManifest.xml");
        assertThat(merged).exists();
        assertThat(Files.readString(merged))
                .contains("package=\"com.example.hello\"")
                .contains("android:minSdkVersion=\"24\"");

        // R.java generated (before compile) and contributed to the source set.
        Path rJava = project.resolve("target/plugin/android-res/gen/com/example/hello/R.java");
        assertThat(rJava).exists();
        assertThat(Files.readString(rJava)).contains("public static final int main");

        // R compiled with the app (the contributed source reached javac) — find it wherever the
        // layout put the classes dir.
        try (var walk = Files.walk(project.resolve("target"))) {
            assertThat(walk.filter(f -> f.getFileName().toString().equals("R.class"))
                            .toList())
                    .isNotEmpty();
        }

        // Dex produced from the compiled classes.
        assertThat(project.resolve("target/plugin/android-dex/dex/classes.dex")).exists();

        // The APK replaced the main artifact under its own extension, assembled + debug-signed.
        assertThat(b.apk()).exists();
        Set<String> entries = new HashSet<>();
        try (ZipFile zip = new ZipFile(b.apk().toFile())) {
            zip.stream().forEach(e -> entries.add(e.getName()));
        }
        assertThat(entries)
                .contains("AndroidManifest.xml", "resources.arsc", "classes.dex", "res/layout/main.xml")
                .anyMatch(name -> name.startsWith("META-INF/") && name.endsWith(".RSA")) // v1
                .contains("META-INF/MANIFEST.MF");
        // v2 signing leaves no entry — verify with the same apksig the plugin signs with.
        assertThat(verifiedByApksig(b.apk())).isTrue();
    }

    /**
     * jk run onto a device: the declared deploy command, against a scripted fake adb (a live
     * {@code adb devices} run is the honest remaining gap — no device in CI).
     */
    @Test
    void deploy_installs_and_starts_the_app_through_adb() throws Exception {
        Built b = built();
        Path adbLog = tmp.resolve("adb.log");
        Path fakeAdb = tmp.resolve("fake-adb");
        Files.writeString(fakeAdb, "#!/bin/sh\necho \"$@\" >> " + adbLog.toAbsolutePath() + "\necho Success\n");
        fakeAdb.toFile().setExecutable(true);

        var deploy = PluginCommands.run(
                b.project(),
                b.cache(),
                "deploy",
                List.of("--adb", fakeAdb.toAbsolutePath().toString()));
        assertThat(deploy.error()).isNull();
        assertThat(deploy.found()).isTrue();
        assertThat(deploy.exit()).isZero();
        String adbCalls = Files.readString(adbLog);
        assertThat(adbCalls)
                .contains("install -r " + b.apk().toAbsolutePath())
                .contains("shell am start -n com.example.hello/com.example.hello.MainActivity");
    }

    /**
     * Instrumented tests: the instrument command against a scripted transcript (a live device run
     * remains gated — the parser and adb argv are the testable surface).
     */
    @Test
    void instrument_runs_the_androidx_runner_and_reads_its_transcript() throws Exception {
        Built b = built();
        Path instrLog = tmp.resolve("instr-adb.log");
        Path instrAdb = tmp.resolve("fake-instr-adb");
        Files.writeString(
                instrAdb,
                "#!/bin/sh\n"
                        + "echo \"$@\" >> " + instrLog.toAbsolutePath() + "\n"
                        + "case \"$*\" in\n"
                        + "  *\"am instrument\"*)\n"
                        + "    printf '%s\\n' \\\n"
                        + "      'INSTRUMENTATION_STATUS: class=com.example.hello.SmokeTest' \\\n"
                        + "      'INSTRUMENTATION_STATUS: test=works' \\\n"
                        + "      'INSTRUMENTATION_STATUS_CODE: 1' \\\n"
                        + "      'INSTRUMENTATION_STATUS: class=com.example.hello.SmokeTest' \\\n"
                        + "      'INSTRUMENTATION_STATUS: test=works' \\\n"
                        + "      'INSTRUMENTATION_STATUS_CODE: 0' \\\n"
                        + "      'INSTRUMENTATION_STATUS: class=com.example.hello.SmokeTest' \\\n"
                        + "      'INSTRUMENTATION_STATUS: test=broken' \\\n"
                        + "      'INSTRUMENTATION_STATUS_CODE: 1' \\\n"
                        + "      'INSTRUMENTATION_STATUS: test=broken' \\\n"
                        + "      'INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: boom' \\\n"
                        + "      'INSTRUMENTATION_STATUS_CODE: -2' \\\n"
                        + "      'INSTRUMENTATION_CODE: -1' ;;\n"
                        + "  *) echo Success ;;\n"
                        + "esac\n");
        instrAdb.toFile().setExecutable(true);
        var instrument = PluginCommands.run(
                b.project(),
                b.cache(),
                "instrument",
                List.of("--adb", instrAdb.toAbsolutePath().toString()));
        assertThat(instrument.error()).isNull();
        assertThat(instrument.found()).isTrue();
        assertThat(instrument.exit()).isEqualTo(1); // the transcript carries one failure
        String instrOut = String.join("\n", instrument.output());
        assertThat(instrOut)
                .contains("✓ com.example.hello.SmokeTest.works")
                .contains("✗ com.example.hello.SmokeTest.broken FAILED")
                .contains("1 passed, 1 failed");
        assertThat(Files.readString(instrLog))
                .contains("install -r " + b.apk().toAbsolutePath())
                .contains("shell am instrument -r -w com.example.hello/androidx.test.runner.AndroidJUnitRunner");
    }

    /** Managed devices: jk avd create/list/boot against the managed SDK root. */
    @Test
    void avd_create_and_list_work_against_the_managed_sdk_root_and_boot_wants_the_emulator() throws Exception {
        Built b = built();
        Path fakeImage = b.sdkRoot().resolve("system-images/android-28/default/x86_64");
        Files.createDirectories(fakeImage);
        var avdCreate = PluginCommands.run(
                b.project(),
                b.cache(),
                "avd",
                List.of("create", "spike", "--system-image", "system-images;android-28;default;x86_64"));
        assertThat(avdCreate.error()).isNull();
        assertThat(avdCreate.exit()).isZero();
        Path avdConfig = b.sdkRoot().resolve("avd/spike.avd/config.ini");
        assertThat(avdConfig).exists();
        assertThat(Files.readString(avdConfig))
                .contains("image.sysdir.1=system-images/android-28/default/x86_64/")
                .contains("tag.id=default");
        var avdList = PluginCommands.run(b.project(), b.cache(), "avd", List.of("list"));
        assertThat(String.join("\n", avdList.output())).contains("spike");
        // boot: refuses gracefully without the emulator component (no ~300MB download in CI).
        var avdBoot = PluginCommands.run(b.project(), b.cache(), "avd", List.of("boot", "spike"));
        assertThat(avdBoot.exit()).isEqualTo(1);
        assertThat(String.join("\n", avdBoot.output())).contains("emulator component is not installed");
    }

    /** The provisioning surface: component status over the same command machinery. */
    @Test
    void android_sdk_reports_the_installed_platform() throws Exception {
        Built b = built();
        var status = PluginCommands.run(b.project(), b.cache(), "android", List.of("sdk"));
        assertThat(status.error()).isNull();
        assertThat(status.exit()).isZero();
        assertThat(String.join("\n", status.output())).contains("platforms;android-28: installed");
    }

    /**
     * The real declared plan, exactly as jk build assembles it, run to completion: the plugin
     * steps present, no errors, a debug-signed APK under target/lib.
     */
    private Built buildHelloWorld() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("hello"));
        writeProject(project);

        // Accept the SDK licenses exactly as `jk android licenses --yes` does — the installer
        // refuses to download otherwise (the gate the AndroidSdkTest covers in isolation).
        var sdk = AndroidSdk.resolve();
        var installer = new AndroidSdkInstaller(sdk);
        if (!sdk.installed("platforms;android-28")) {
            for (var license : installer.feed().licenses().entrySet()) {
                sdk.recordLicense(license.getKey(), AndroidRepoFeed.licenseHash(license.getValue()));
            }
        }

        Cas cas = new Cas(CACHE);
        // The platform is PROVIDED via the manifest's [[contribute.provided-classpath]] — the
        // lock carries no platform artifact at all.
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        List.of(),
                        List.of()),
                project.resolve("jk-lock.toml"));

        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                CACHE,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        BuildPlan plan = BuildPlanner.fullPlan(in);

        assertThat(plan.steps().stream().map(p -> p.name()))
                .contains("plugin-android-manifest", "plugin-android-res", "plugin-android-dex", "package-jar");

        BuildPlanResult result = plan.run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        return new Built(project, CACHE, SDK_ROOT, project.resolve("target/lib/hello-1.0.0.apk"));
    }

    /**
     * apksig's verifier — proves v1+v2 without an emulator. apksig rides its own test
     * configuration: the plugin worker jar is non-transitive (it resolves deps from the store at
     * run time), so it does not carry apksig's classes.
     */
    private static boolean verifiedByApksig(Path apk) throws Exception {
        List<URL> urls = new ArrayList<>();
        String cp = System.getProperty("jk.android.apksig.classpath", "");
        if (!cp.isBlank()) {
            // A harness may hand apksig over as a classpath property.
            for (String part : cp.split(File.pathSeparator)) {
                if (!part.isBlank()) urls.add(Path.of(part).toUri().toURL());
            }
        } else {
            // Otherwise apksig is a dependency of the android worker itself, so rebuild -cp from
            // the worker POM (jk.android.plugin.jar is set
            // by [build] test-plugin-jars).
            String workerJar = System.getProperty("jk.android.plugin.jar", "");
            assertThat(workerJar)
                    .as("jk.android.apksig.classpath or jk.android.plugin.jar system property")
                    .isNotBlank();
            for (Path p : WorkerLaunchClasspath.paths(Path.of(workerJar))) {
                urls.add(p.toUri().toURL());
            }
        }
        try (var loader = new URLClassLoader(urls.toArray(new URL[0]))) {
            Class<?> builderClass = loader.loadClass("com.android.apksig.ApkVerifier$Builder");
            Object builder = builderClass.getConstructor(File.class).newInstance(apk.toFile());
            Object verifier = builderClass.getMethod("build").invoke(builder);
            Object apkResult = verifier.getClass().getMethod("verify").invoke(verifier);
            return (boolean) apkResult.getClass().getMethod("isVerified").invoke(apkResult);
        }
    }

    private static void writeProject(Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                name    = "hello"
                group   = "com.example"
                version = "1.0.0"
                java    = 17

                [android]
                namespace   = "com.example.hello"
                compile-sdk = 28
                min-sdk     = 24

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
                """);
        Files.writeString(project.resolve("AndroidManifest.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="@string/app_name">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN"/>
                                <category android:name="android.intent.category.LAUNCHER"/>
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """);
        Path res = Files.createDirectories(project.resolve("res"));
        Files.createDirectories(res.resolve("values"));
        Files.writeString(res.resolve("values/strings.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">Hello jk</string>
                    <string name="hello">Hello from jk!</string>
                </resources>
                """);
        Files.createDirectories(res.resolve("layout"));
        Files.writeString(res.resolve("layout/main.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:gravity="center"
                    android:text="@string/hello"/>
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/hello"));
        Files.writeString(src.resolve("MainActivity.java"), """
                package com.example.hello;

                import android.app.Activity;
                import android.os.Bundle;

                /** The Compose-less hello world (android-plan Step 1's exit shape). */
                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.main);
                    }
                }
                """);
    }
}
