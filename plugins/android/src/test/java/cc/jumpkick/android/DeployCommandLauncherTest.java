// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which component {@code deploy} starts: the one the launcher would, read off the merged manifest
 * the packager leaves beside the artifact.
 */
class DeployCommandLauncherTest {

    private static final String MAIN = "<action android:name=\"android.intent.action.MAIN\"/>";
    private static final String LAUNCHER = "<category android:name=\"android.intent.category.LAUNCHER\"/>";

    /** A MAIN activity with no LAUNCHER category is an entry point, not what a tap on the icon opens. */
    @Test
    void a_main_activity_without_the_launcher_category_is_passed_over(@TempDir Path tmp) throws Exception {
        Path manifest = manifest(tmp, """
                <activity android:name=".DebugEntry">
                  <intent-filter>%s</intent-filter>
                </activity>
                <activity android:name=".MainActivity">
                  <intent-filter>%s%s</intent-filter>
                </activity>
                """.formatted(MAIN, MAIN, LAUNCHER));

        assertThat(DeployCommand.launcherActivity(manifest, "com.example")).isEqualTo("com.example.MainActivity");
    }

    /** MAIN and LAUNCHER count only in the same filter. */
    @Test
    void main_and_launcher_in_different_filters_do_not_launch(@TempDir Path tmp) throws Exception {
        Path manifest = manifest(tmp, """
                <activity android:name=".Split">
                  <intent-filter>%s</intent-filter>
                  <intent-filter>%s</intent-filter>
                </activity>
                """.formatted(MAIN, LAUNCHER));

        assertThatThrownBy(() -> DeployCommand.launcherActivity(manifest, "com.example"))
                .hasMessageContaining("no MAIN/LAUNCHER activity");
    }

    /**
     * An app that switches its icon declares the launcher on {@code <activity-alias>} elements, one
     * enabled; the alias is a component {@code am start} starts by name.
     */
    @Test
    void an_enabled_activity_alias_is_the_launcher(@TempDir Path tmp) throws Exception {
        Path manifest = manifest(tmp, """
                <activity android:name="com.example.ui.MainActivity"/>
                <activity-alias android:name=".IconDark" android:enabled="false"
                    android:targetActivity="com.example.ui.MainActivity">
                  <intent-filter>%s%s</intent-filter>
                </activity-alias>
                <activity-alias android:name=".IconLight" android:targetActivity="com.example.ui.MainActivity">
                  <intent-filter>%s%s</intent-filter>
                </activity-alias>
                """.formatted(MAIN, LAUNCHER, MAIN, LAUNCHER));

        assertThat(DeployCommand.launcherActivity(manifest, "com.example")).isEqualTo("com.example.IconLight");
    }

    /** Android reads a class name with no dot in it as relative to the package, as it does {@code .Name}. */
    @Test
    void a_bare_class_name_resolves_against_the_app_id(@TempDir Path tmp) throws Exception {
        Path manifest = manifest(tmp, """
                <activity android:name="MainActivity">
                  <intent-filter>%s%s</intent-filter>
                </activity>
                """.formatted(MAIN, LAUNCHER));

        assertThat(DeployCommand.launcherActivity(manifest, "com.example")).isEqualTo("com.example.MainActivity");
    }

    /**
     * Deploy reads the manifest beside the artifact, not the module's source manifest. A debug APK
     * and a release AAB share a stem, so the whole artifact name keys it.
     */
    @Test
    void the_merged_manifest_sits_beside_the_artifact_under_its_whole_name(@TempDir Path tmp) {
        assertThat(AndroidDeps.mergedManifest(tmp.resolve("target/app-1.0.0.apk")))
                .isEqualTo(tmp.resolve("target/app-1.0.0.apk.AndroidManifest.xml"));
        assertThat(AndroidDeps.mergedManifest(tmp.resolve("target/app-1.0.0.aab")))
                .isEqualTo(tmp.resolve("target/app-1.0.0.aab.AndroidManifest.xml"));
    }

    private static Path manifest(Path dir, String components) throws Exception {
        return Files.writeString(dir.resolve("app-AndroidManifest.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
                  <application>
                %s  </application>
                </manifest>
                """.formatted(components));
    }
}
