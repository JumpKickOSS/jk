// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PackageIdTest {

    @Test
    void bare_ga_parses_as_default_jar() {
        PackageId id = PackageId.parse("com.foo:bar");
        assertThat(id.ga()).isEqualTo("com.foo:bar");
        assertThat(id.type()).isEqualTo("jar");
        assertThat(id.classifier()).isEmpty();
        assertThat(id.key()).isEqualTo("com.foo:bar:jar:");
        assertThat(id.isDefaultJar()).isTrue();
    }

    @Test
    void full_key_round_trips() {
        PackageId id = PackageId.parse("io.netty:netty-transport-native-epoll:jar:linux-x86_64");
        assertThat(id.ga()).isEqualTo("io.netty:netty-transport-native-epoll");
        assertThat(id.classifier()).isEqualTo("linux-x86_64");
        assertThat(id.key()).isEqualTo("io.netty:netty-transport-native-epoll:jar:linux-x86_64");
        assertThat(id.display()).isEqualTo("io.netty:netty-transport-native-epoll:linux-x86_64");
    }

    @Test
    void withVersion_carries_classifier_and_type() {
        Coordinate c = PackageId.parse("g:a:test-jar:tests").withVersion("1.2.3");
        assertThat(c.group()).isEqualTo("g");
        assertThat(c.artifact()).isEqualTo("a");
        assertThat(c.version()).isEqualTo("1.2.3");
        assertThat(c.classifier()).isEqualTo("tests");
        assertThat(c.type()).isEqualTo("test-jar");
    }

    @Test
    void ofGa_rejects_multi_colon() {
        assertThatThrownBy(() -> PackageId.ofGa("g:a:extra")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void display_omits_default_jar_type() {
        assertThat(PackageId.parse("org.jetbrains.kotlin:kotlin-build-tools-api:jar:")
                        .display())
                .isEqualTo("org.jetbrains.kotlin:kotlin-build-tools-api");
        assertThat(PackageId.parse("com.android.support:support-compat:aar:").display())
                .isEqualTo("com.android.support:support-compat!aar");
    }
}
