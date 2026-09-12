// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CoordinateTest {

    @Test
    void parses_gav() {
        Coordinate c = Coordinate.parse("com.fasterxml.jackson.core:jackson-databind:2.18.2");
        assertThat(c.group()).isEqualTo("com.fasterxml.jackson.core");
        assertThat(c.artifact()).isEqualTo("jackson-databind");
        assertThat(c.version()).isEqualTo("2.18.2");
        assertThat(c.classifier()).isNull();
        assertThat(c.type()).isEqualTo("jar");
        assertThat(c.toString()).isEqualTo("com.fasterxml.jackson.core:jackson-databind:2.18.2");
    }

    @Test
    void parses_gav_with_classifier_and_type() {
        Coordinate c = Coordinate.parse("io.netty:netty-transport-native-epoll:4.1.115:linux-x86_64!jar");
        assertThat(c.classifier()).isEqualTo("linux-x86_64");
        assertThat(c.type()).isEqualTo("jar");
        assertThat(c.toString()).isEqualTo("io.netty:netty-transport-native-epoll:4.1.115:linux-x86_64");
    }

    @Test
    void parses_non_jar_type_with_bang() {
        Coordinate c = Coordinate.parse("com.example:widget:1.0.0!pom");
        assertThat(c.type()).isEqualTo("pom");
        assertThat(c.toString()).isEqualTo("com.example:widget:1.0.0!pom");
    }

    @Test
    void android_aar_round_trips_and_omits_default_jar_noise() {
        Coordinate aar = Coordinate.parse("androidx.core:core:1.13.1!aar");
        assertThat(aar.type()).isEqualTo("aar");
        assertThat(aar.toString()).isEqualTo("androidx.core:core:1.13.1!aar");

        Coordinate jar = Coordinate.parse("androidx.core:core:1.13.1");
        assertThat(jar.type()).isEqualTo("jar");
        assertThat(jar.toString()).isEqualTo("androidx.core:core:1.13.1"); // no !jar
    }

    @Test
    void module_strips_version() {
        Coordinate c = Coordinate.of("com.foo", "bar", "1.0");
        assertThat(c.module()).isEqualTo("com.foo:bar");
    }

    @Test
    void rejects_malformed() {
        assertThatThrownBy(() -> Coordinate.parse("foo:bar")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_at_as_packaging_type() {
        assertThatThrownBy(() -> Coordinate.parse("com.example:widget:1.0.0@pom"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("!");
    }

    /**
     * Every field becomes a path segment under a store or {@code ~/.m2} root, and the values come
     * from a cloned project's lockfile; a segment that could climb out of the layout is refused at
     * construction so no later resolve has to remember to check.
     */
    @Test
    void rejects_segments_that_escape_a_maven_layout() {
        assertThatThrownBy(() -> Coordinate.of("com.foo", "a", "../../evil"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> Coordinate.of("com.foo", "..", "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifact");
        assertThatThrownBy(() -> Coordinate.of("com.foo", "a/b", "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifact");
        assertThatThrownBy(() -> Coordinate.of("com..foo", "a", "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
        assertThatThrownBy(() -> Coordinate.of("com/foo", "a", "1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
        assertThatThrownBy(() -> new Coordinate("com.foo", "a", "1.0", "../x", "jar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classifier");
        assertThatThrownBy(() -> new Coordinate("com.foo", "a", "1.0", null, "jar/../x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void ordinary_maven_versions_and_ranges_are_accepted() {
        assertThat(Coordinate.of("com.foo", "a", "1.0-SNAPSHOT").version()).isEqualTo("1.0-SNAPSHOT");
        assertThat(Coordinate.of("com.foo", "a", "[1.0,2.0)").version()).isEqualTo("[1.0,2.0)");
        assertThat(Coordinate.of("com.foo", "a", "2.0.0.Final").version()).isEqualTo("2.0.0.Final");
        assertThat(Coordinate.of("org.foo-bar.x_y", "a.b-c", "1").group()).isEqualTo("org.foo-bar.x_y");
    }
}
