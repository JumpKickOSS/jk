// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.JlinkImage;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.Jlink;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.testing.Symlinks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Steps whose output embeds or links the runtime key the JDK's whole platform: two homes that
 * compile alike (one compiler, one version) but name another OS and architecture give a jlink image
 * and a native image two keys.
 */
class PlatformJdkKeysTest {

    @Test
    void jlink_and_native_image_keys_differ_across_os_and_arch(@TempDir Path tmp) throws Exception {
        Path linux = home(tmp.resolve("linux"), "Linux", "x86_64");
        Path mac = home(tmp.resolve("mac"), "Darwin", "aarch64");
        assertThat(ActionKey.compileJdkToken(linux))
                .as("the two homes compile alike")
                .isEqualTo(ActionKey.compileJdkToken(mac));

        assertThat(jlinkTokens(linux)).isNotEqualTo(jlinkTokens(mac));
        assertThat(imageKey(tmp, linux)).isNotEqualTo(imageKey(tmp, mac));
    }

    private static List<String> jlinkTokens(Path home) throws IOException {
        return PlannerJlink.tokens(
                "jar", "libs", List.of(), "app.Main", home, 25, Jlink.defaults(), JlinkImage.Cache.AOT);
    }

    private static String imageKey(Path tmp, Path home) throws Exception {
        Path jar = tmp.resolve("app.jar");
        if (!Files.exists(jar)) Files.writeString(jar, "app");
        return PlannerNative.imageKey(
                        home,
                        List.of(jar),
                        List.of(),
                        "app.Main",
                        false,
                        tmp.resolve("target/native/app"),
                        null,
                        null,
                        List.of(),
                        ClasspathFingerprint.ON_DISK)
                .key();
    }

    /** A home naming {@code os}/{@code arch} over the running JDK's own {@code lib/}. */
    private static Path home(Path home, String os, String arch) throws IOException {
        Files.createDirectories(home);
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"25.0.5\"\nOS_NAME=\"" + os + "\"\nOS_ARCH=\"" + arch + "\"\n");
        Symlinks.create(
                home.resolve("lib"),
                JavaHomes.runningJavaHome().toAbsolutePath().resolve("lib"));
        return home;
    }
}
