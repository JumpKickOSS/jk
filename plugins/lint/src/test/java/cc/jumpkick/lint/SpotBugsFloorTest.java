// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The floor table, the version order it is judged by, and the JDK feature read off a release file. */
class SpotBugsFloorTest {

    @Test
    void each_lts_line_has_its_floor_and_a_jdk_between_lines_takes_the_older_one() {
        assertThat(SpotBugsFloor.floorFor(17)).contains("4.2.2");
        assertThat(SpotBugsFloor.floorFor(19)).contains("4.2.2");
        assertThat(SpotBugsFloor.floorFor(21)).contains("4.8.0");
        assertThat(SpotBugsFloor.floorFor(25)).contains("4.9.4");
        assertThat(SpotBugsFloor.floorFor(26)).contains("4.9.4");
        assertThat(SpotBugsFloor.floorFor(11)).isEmpty();
    }

    @Test
    void versions_order_by_numeric_segment() {
        assertThat(SpotBugsFloor.below("4.7.3", "4.8.0")).isTrue();
        assertThat(SpotBugsFloor.below("4.10.4", "4.9.4")).isFalse();
        assertThat(SpotBugsFloor.below("4.8", "4.8.0")).isFalse();
        assertThat(SpotBugsFloor.below("4.8.0", "4.8.0")).isFalse();
        assertThat(SpotBugsFloor.below("4.9.4-SNAPSHOT", "4.9.4"))
                .as("not dotted numbers: left to SpotBugs")
                .isFalse();
    }

    @Test
    void the_jdk_feature_is_read_off_the_release_file(@TempDir Path tmp) throws Exception {
        Path jdk25 = Files.createDirectories(tmp.resolve("jdk25"));
        Files.writeString(
                jdk25.resolve("release"), "JAVA_VERSION=\"25.0.4.1\"\nOS_ARCH=\"x86_64\"\n", StandardCharsets.UTF_8);
        Path jdk8 = Files.createDirectories(tmp.resolve("jdk8"));
        Files.writeString(jdk8.resolve("release"), "JAVA_VERSION=\"1.8.0_402\"\n", StandardCharsets.UTF_8);

        assertThat(SpotBugsFloor.feature(jdk25)).isEqualTo(25);
        assertThat(SpotBugsFloor.feature(jdk8)).isEqualTo(8);
        assertThat(SpotBugsFloor.feature(tmp.resolve("missing"))).isZero();
    }

    @Test
    void the_check_refuses_below_the_floor_and_passes_at_or_above_it(@TempDir Path tmp) throws Exception {
        Path jdk21 = Files.createDirectories(tmp.resolve("jdk21"));
        Files.writeString(jdk21.resolve("release"), "JAVA_VERSION=\"21.0.12.1\"\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SpotBugsFloor.check("4.7.3", jdk21))
                .hasMessage("SpotBugs 4.7.3 cannot read the class files of JDK 21, which this step runs on; the oldest"
                        + " release that can is 4.8.0: set [lint] spotbugs-version = \"4.8.0\" or newer");
        assertThatCode(() -> SpotBugsFloor.check("4.8.0", jdk21)).doesNotThrowAnyException();
        assertThatCode(() -> SpotBugsFloor.check("4.10.4", jdk21)).doesNotThrowAnyException();
        assertThatCode(() -> SpotBugsFloor.check("4.7.3", tmp.resolve("no-release-file")))
                .as("an unreadable JDK is left to SpotBugs")
                .doesNotThrowAnyException();
    }
}
