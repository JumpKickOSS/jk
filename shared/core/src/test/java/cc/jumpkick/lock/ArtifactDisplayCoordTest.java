// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtifactDisplayCoordTest {

    @Test
    void default_jar_is_plain_gav() {
        Lockfile.Artifact pkg = pkg("com.foo:bar:jar:", "1.2.3");
        assertThat(pkg.displayIdentity()).isEqualTo("com.foo:bar");
        assertThat(pkg.displayCoord()).isEqualTo("com.foo:bar:1.2.3");
        assertThat(pkg.displayCoord()).isEqualTo(pkg.coordinate().toString());
    }

    @Test
    void bare_ga_name_includes_version() {
        Lockfile.Artifact pkg = pkg("com.foo:bar", "9.0");
        assertThat(pkg.displayIdentity()).isEqualTo("com.foo:bar");
        assertThat(pkg.displayCoord()).isEqualTo("com.foo:bar:9.0");
    }

    @Test
    void classifier_rides_after_version() {
        Lockfile.Artifact pkg = pkg("io.netty:netty-transport-native-epoll:jar:linux-x86_64", "4.1.118.Final");
        assertThat(pkg.displayIdentity()).isEqualTo("io.netty:netty-transport-native-epoll:linux-x86_64");
        assertThat(pkg.displayCoord()).isEqualTo("io.netty:netty-transport-native-epoll:4.1.118.Final:linux-x86_64");
    }

    @Test
    void android_aar_uses_bang_type_after_version() {
        Lockfile.Artifact pkg = pkg("androidx.core:core:aar:", "1.13.1");
        assertThat(pkg.isAar()).isTrue();
        assertThat(pkg.displayIdentity()).isEqualTo("androidx.core:core!aar");
        // Same shape Coordinate.parse accepts — type only when non-jar.
        assertThat(pkg.displayCoord()).isEqualTo("androidx.core:core:1.13.1!aar");
        assertThat(pkg.coordinate().toString()).isEqualTo("androidx.core:core:1.13.1!aar");
        assertThat(Coordinate.parse(pkg.displayCoord()).type()).isEqualTo("aar");
    }

    @Test
    void aar_detected_from_path_even_with_jar_package_key() {
        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.acme:widgets:jar:",
                "2.0",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:0",
                "widgets-2.0.aar",
                List.of(Scope.MAIN),
                List.of());
        assertThat(pkg.isAar()).isTrue();
        assertThat(pkg.displayCoord()).isEqualTo("com.acme:widgets:2.0!aar");
    }

    private static Lockfile.Artifact pkg(String name, String version) {
        return new Lockfile.Artifact(
                name,
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:0",
                null,
                List.of(Scope.MAIN),
                List.of());
    }
}
