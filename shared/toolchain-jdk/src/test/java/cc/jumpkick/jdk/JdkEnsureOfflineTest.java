// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.RequestShell;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build whose JDK feed cannot be reached: the feed and every archive it names answer 404 through a
 * loopback mirror, and the cached feed is days past its TTL. An unpinned module still builds on the
 * JDK jk runs on; a pinned one fails naming the JDK and the command that installs it.
 */
class JdkEnsureOfflineTest {

    private static final List<String> OVERLAID = List.of("JK_STORE_DIR", "JK_STATE_DIR", JdkMirror.ENV);

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    @AfterEach
    void reset() {
        JdkEnsure.resetSharedRegistries();
        for (String name : OVERLAID) System.clearProperty("jk.env." + name);
    }

    @Test
    void an_unpinned_module_builds_on_the_running_jdk_when_the_feed_is_unreachable_and_its_cache_expired(
            @TempDir Path tmp) throws Exception {
        Path store = offlineHome(tmp);
        Path cache = Files.writeString(store.resolve("jdks.json"), feed());
        Files.setLastModifiedTime(cache, FileTime.from(Instant.now().minus(Duration.ofDays(3))));
        List<String> warnings = new CopyOnWriteArrayList<>();

        JdkEnsure.Outcome outcome = ensureWithoutAmbientJdk(tmp, null, warnings);

        assertThat(outcome.jdkOpt())
                .get()
                .extracting(InstalledJdk::home)
                .isEqualTo(Path.of(System.getProperty("java.home"))
                        .toAbsolutePath()
                        .normalize());
        assertThat(warnings).anySatisfy(w -> assertThat(w)
                .contains("could not install temurin-" + JdkLts.OFFLINE_LATEST_LTS)
                .contains("the JDK jk runs on"));
        assertThat(http.requested())
                .as("the refresh was tried and did not matter")
                .isNotEmpty();
    }

    @Test
    void a_pinned_module_with_no_satisfying_jdk_fails_naming_the_jdk_and_the_install_command(@TempDir Path tmp)
            throws Exception {
        offlineHome(tmp);

        assertThatThrownBy(() -> ensureWithoutAmbientJdk(tmp, "temurin-21", new CopyOnWriteArrayList<>()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("JDK temurin-21 is not installed")
                .hasMessageContaining("jk jdk install temurin-21");
    }

    /** A store and state of this test's own, with every feed and archive request going to a 404ing mirror. */
    private Path offlineHome(Path tmp) throws IOException {
        Path store = Files.createDirectories(tmp.resolve("store"));
        System.setProperty("jk.env.JK_STORE_DIR", store.toString());
        System.setProperty("jk.env.JK_STATE_DIR", tmp.resolve("state").toString());
        System.setProperty(
                "jk.env." + JdkMirror.ENV, http.base().resolve("/mirror/").toString());
        return store;
    }

    /** Ensure with an empty JDK root and a shell whose PATH holds no javac and that sets no JAVA_HOME. */
    private static JdkEnsure.Outcome ensureWithoutAmbientJdk(Path tmp, @Nullable String pin, List<String> warnings)
            throws Exception {
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path emptyPath = Files.createDirectories(tmp.resolve("bin"));
        return RequestShell.where(
                Map.of("PATH", emptyPath.toString()),
                () -> JdkEnsure.ensure(project, jdks, pin, 17, null, warnings::add, true));
    }

    /** A feed naming the bootstrap LTS and 21 for this host, both at archives the mirror does not have. */
    private static String feed() {
        return "{\n  \"jdks\": [\n" + block(JdkLts.OFFLINE_LATEST_LTS) + ",\n" + block(21) + "\n  ]\n}\n";
    }

    private static String block(int major) {
        String version = major + ".0.1";
        return """
                    {
                      "vendor": "Eclipse",
                      "product": "Temurin",
                      "default": true,
                      "jdk_version_major": %d,
                      "jdk_version": "%s",
                      "suggested_sdk_name": "temurin-%d",
                      "shared_index_aliases": ["temurin-%s", "temurin-%d", "%s", "%d"],
                      "packages": [
                        {
                          "os": "%s",
                          "arch": "%s",
                          "version": "%s",
                          "url": "https://vendor.example/temurin-%s.zip",
                          "package_type": "zip",
                          "package_to_java_home_prefix": "",
                          "install_folder_name": "temurin-%s",
                          "archive_size": 2048,
                          "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                        }
                      ]
                    }""".formatted(
                        major,
                        version,
                        major,
                        version,
                        major,
                        version,
                        major,
                        HostPlatform.currentOs(),
                        HostPlatform.currentArch(),
                        version,
                        version,
                        version);
    }
}
