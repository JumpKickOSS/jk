// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkVendor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GradleJvmCompatibilityTest {

    @Test
    void each_gradle_line_has_the_newest_jdk_it_runs_on() {
        assertThat(GradleJvmCompatibility.maxRunningJdk("8.3")).isEqualTo(20);
        assertThat(GradleJvmCompatibility.maxRunningJdk("8.14.2")).isEqualTo(24);
        assertThat(GradleJvmCompatibility.maxRunningJdk("9.0.0")).isEqualTo(24);
        assertThat(GradleJvmCompatibility.maxRunningJdk("9.7.1")).isEqualTo(25);
        assertThat(GradleJvmCompatibility.maxRunningJdk("9.9.0-milestone-1")).isEqualTo(25);
        assertThat(GradleJvmCompatibility.maxRunningJdk("7.6.4")).isEqualTo(19);
        assertThat(GradleJvmCompatibility.maxRunningJdk("6.8")).isEqualTo(15);
        assertThat(GradleJvmCompatibility.maxRunningJdk("wrapper")).isEqualTo(25);
        assertThat(GradleJvmCompatibility.minRunningJdk("9.5.1")).isEqualTo(17);
        assertThat(GradleJvmCompatibility.minRunningJdk("8.14")).isEqualTo(8);
    }

    @Test
    void the_engines_own_jdk_is_used_when_the_distribution_accepts_it() throws IOException {
        Path running = Path.of("/jdks/25");

        GradleJvmCompatibility.Pick pick = GradleJvmCompatibility.pick("9.7.1", running, 25, List::of);

        assertThat(pick.javaHome()).isEqualTo(running);
        assertThat(pick.major()).isEqualTo(25);
    }

    @Test
    void an_older_wrapper_runs_on_the_newest_installed_jdk_under_its_ceiling() throws IOException {
        List<JdkHit> installed =
                List.of(hit("/jdks/25", "25.0.4.1"), hit("/jdks/17", "17.0.20.1"), hit("/jdks/21", "21.0.12.1"));

        GradleJvmCompatibility.Pick pick = GradleJvmCompatibility.pick("8.3", Path.of("/jdks/25"), 25, () -> installed);

        assertThat(pick.javaHome()).isEqualTo(Path.of("/jdks/17"));
        assertThat(pick.major()).isEqualTo(17);
    }

    @Test
    void no_jdk_in_range_is_refused_naming_the_range_and_the_install_command() {
        assertThatThrownBy(() -> GradleJvmCompatibility.pick(
                        "8.3", Path.of("/jdks/25"), 25, () -> List.of(hit("/jdks/25", "25.0.4.1"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Gradle 8.3 runs on JDK 8 to 20")
                .hasMessageContaining("jk jdk install 20");
    }

    private static JdkHit hit(String home, String version) {
        return new JdkHit(Path.of(home), version, JdkVendor.fromFeed("Eclipse Adoptium", "Temurin"), "test");
    }
}
