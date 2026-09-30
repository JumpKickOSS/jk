// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkUninstallPolicy;
import cc.jumpkick.testing.FakeJdk;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoursierProbeTest {

    @Test
    void the_platform_cache_is_read_when_the_override_is_unset(@TempDir Path tmp) throws IOException {
        Path home = FakeJdk.create(tmp.resolve(".cache/coursier/jvm/temurin@21"), "21.0.5");

        List<JdkHit> hits = new CoursierProbe(name -> null, tmp.toString(), "Linux").discoverAllJdks();

        assertThat(hits).extracting(JdkHit::home).containsExactly(home.toRealPath());
        assertThat(hits).allSatisfy(h -> assertThat(h.source()).isEqualTo("coursier"));
        assertThat(JdkUninstallPolicy.removable("coursier")).isFalse();
    }

    @Test
    void the_override_is_the_only_directory_consulted(@TempDir Path tmp) throws IOException {
        Path home = FakeJdk.create(tmp.resolve("custom/zulu@25"), "25.0.1");
        FakeJdk.create(tmp.resolve("user/.cache/coursier/jvm/temurin@21"), "21.0.5");
        Map<String, String> env =
                Map.of("COURSIER_JVM_CACHE", tmp.resolve("custom").toString());

        List<JdkHit> hits = new CoursierProbe(env::get, tmp.resolve("user").toString(), "Linux").discoverAllJdks();

        assertThat(hits).extracting(JdkHit::home).containsExactly(home.toRealPath());
    }

    @Test
    void a_macos_bundle_unwraps_contents_home(@TempDir Path tmp) throws IOException {
        Path home = FakeJdk.create(tmp.resolve("Library/Caches/Coursier/jvm/temurin@21/Contents/Home"), "21.0.5");

        List<JdkHit> hits = new CoursierProbe(name -> null, tmp.toString(), "Mac OS X").discoverAllJdks();

        assertThat(hits).extracting(JdkHit::home).containsExactly(home.toRealPath());
    }

    @Test
    void platform_cache_directories() {
        assertThat(CoursierProbe.jvmCacheDir(name -> null, "/home/u", "Linux"))
                .isEqualTo(Path.of("/home/u/.cache/coursier/jvm"));
        assertThat(CoursierProbe.jvmCacheDir(name -> null, "/Users/u", "Mac OS X"))
                .isEqualTo(Path.of("/Users/u/Library/Caches/Coursier/jvm"));
        assertThat(CoursierProbe.jvmCacheDir(name -> null, "/u", "Windows 11"))
                .isEqualTo(Path.of("/u", "AppData", "Local", "Coursier", "Cache", "jvm"));
    }

    @Test
    void a_missing_cache_is_empty(@TempDir Path tmp) throws IOException {
        assertThat(new CoursierProbe(name -> null, tmp.toString(), "Linux").discoverAllJdks())
                .isEmpty();
    }
}
