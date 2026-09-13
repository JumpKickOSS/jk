// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.androidsdk.AndroidRepoFeed;
import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.androidsdk.AndroidSdkInstaller;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan Step 2 acceptance, remote half: a real androidx dependency — published as an AAR —
 * resolves through the ordinary lock plan (the effective POM's {@code packaging} decides the
 * fetch extension; the lock's {@code path} records it), materializes as an exploded container, and
 * flows into compile (classes.jar), the app link (its {@code R.txt} regenerates a final-id
 * {@code R} under its namespace), and the dex closure.
 *
 * <p>Network test against Google Maven; the CAS persists under build/ so repeat runs are warm.
 */
@Tag("slow")
@ExtendWith(SysProps.class)
class AndroidRemoteAarTest {

    @Test
    void androidx_aar_resolves_explodes_and_builds(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = TestCaches.dir("android-spike-cache");
        Path sdkRoot = TestCaches.dir("android-spike-sdk");
        System.setProperty(AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());

        writeProject(project);
        acceptLicenses();

        // ---- 1. jk lock: packaging-aware resolution ----
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        BuildPlanResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();
        assertThat(lockResult.success()).isTrue();

        var lockfile = LockfileReader.read(project.resolve("jk-lock.toml"));
        var annotationAar = lockfile.artifacts().stream()
                .filter(a -> a.matchesModule("androidx.core:core"))
                .findFirst()
                .orElseThrow();
        assertThat(annotationAar.isAar()).isTrue();
        assertThat(annotationAar.path()).endsWith(".aar");
        assertThat(annotationAar.checksum()).startsWith("sha256:");
        // SDK component pins recorded ([[sdk]] — the platform this project compiles against).
        assertThat(lockfile.sdk()).anyMatch(e -> e.component().equals("platforms;android-34"));

        // ---- 2. jk sync: the AAR re-fetches under its real extension ----
        var sync = new CacheSync(new Cas(cache), new Http()).sync(lockfile);
        assertThat(sync.errors()).isEmpty();

        // ---- 3. jk build: compile against classes.jar, R from R.txt, dex the closure ----
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
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
        BuildPlanResult result = BuildPlanner.fullPlan(in).run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();

        // androidx.core ships res + R.txt: its R regenerated under its namespace with final ids.
        Path depR = project.resolve("target/plugin/android-res/gen/androidx/core/R.java");
        assertThat(depR).exists();
        assertThat(Files.readString(depR)).contains("package androidx.core;").contains("0x7f");

        // The APK exists — compile (ContextCompat reference) and the dexed closure both held.
        assertThat(project.resolve("target/lib/remote-1.0.0.apk")).exists();
    }

    private static void acceptLicenses() throws Exception {
        var sdk = AndroidSdk.resolve();
        var installer = new AndroidSdkInstaller(sdk);
        if (!sdk.installed("platforms;android-34")) {
            for (var license : installer.feed().licenses().entrySet()) {
                sdk.recordLicense(license.getKey(), AndroidRepoFeed.licenseHash(license.getValue()));
            }
        }
    }

    private static void writeProject(Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                name    = "remote"
                group   = "com.example"
                version = "1.0.0"
                java    = 17

                [android]
                namespace   = "com.example.remote"
                compile-sdk = 34
                min-sdk     = 24

                [dependencies]
                core = { group = "androidx.core", name = "core", version = "=1.13.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
                """);
        Files.writeString(project.resolve("AndroidManifest.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application>
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN"/>
                                <category android:name="android.intent.category.LAUNCHER"/>
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/remote"));
        Files.writeString(src.resolve("MainActivity.java"), """
                package com.example.remote;

                import android.app.Activity;
                import android.os.Bundle;
                import androidx.core.content.ContextCompat;

                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        int color = ContextCompat.getColor(this, android.R.color.black);
                    }
                }
                """);
    }
}
