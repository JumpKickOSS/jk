// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A classpath launcher grants its program native access on every JDK that knows the flag, so a
 * restricted FFM call from the program's unnamed module prints no warning; a JDK older than the
 * flag gets a launcher it can start.
 */
class AppLauncherScriptTest {

    @TempDir
    Path tmp;

    @Test
    void a_classpath_launcher_on_a_current_jdk_enables_native_access() throws Exception {
        Path jdk = jdk("25.0.1");
        String script = AppLauncher.renderScript(jdk, "com.example.Main", List.of(tmp.resolve("a.jar")));
        assertThat(script).contains(" " + AppLauncher.NATIVE_ACCESS_FLAG + " -cp ");
    }

    @Test
    void a_jdk_that_predates_the_flag_gets_a_launcher_without_it() throws Exception {
        Path jdk = jdk("1.8.0_392");
        String script = AppLauncher.renderScript(jdk, "com.example.Main", List.of(tmp.resolve("a.jar")));
        assertThat(script).doesNotContain("--enable-native-access").contains(" -cp ");
    }

    @Test
    void a_jdk_with_no_release_file_is_read_as_current() {
        assertThat(AppLauncher.featureVersion(tmp.resolve("missing"))).isZero();
        assertThat(AppLauncher.jvmFlags(tmp.resolve("missing"))).startsWith(AppLauncher.NATIVE_ACCESS_FLAG);
    }

    @Test
    void the_feature_release_is_read_from_both_version_spellings() throws Exception {
        assertThat(AppLauncher.featureVersion(jdk("21.0.2"))).isEqualTo(21);
        assertThat(AppLauncher.featureVersion(jdk("1.8.0_392"))).isEqualTo(8);
        assertThat(AppLauncher.featureVersion(jdk("17"))).isEqualTo(17);
    }

    private Path jdk(String javaVersion) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("jdk-" + javaVersion));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + javaVersion + "\"\nIMPLEMENTOR=\"Test\"\n");
        return home;
    }
}
