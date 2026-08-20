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
}
