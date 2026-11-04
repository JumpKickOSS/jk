// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock run under a {@code ~/.jk/config.toml} that does not parse fails naming the file and dials
 * nothing — not the mirror it declares, and not the public repositories it would have stood in
 * for. Fixing the file is enough for the next lock in the same process: the resident engine needs
 * no restart.
 */
@Tag("integration")
class BrokenUserConfigLockTest {

    private static final String GROUP = "com.example.brokenconfig";
    private static final String ARTIFACT = "lib";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    /** Fresh per run, so neither the process memos nor a warm store can answer for the stub. */
    private final String version = "1." + Long.toString(System.nanoTime(), 36);

    private @Nullable String priorSettings;
    private @Nullable String priorHome;

    @TempDir
    Path tmp;

    @BeforeEach
    void isolate() throws Exception {
        priorSettings = System.getProperty(MavenSettings.SETTINGS_PROPERTY);
        priorHome = System.getProperty("jk.env.JK_HOME");
        Path home = Files.createDirectories(tmp.resolve("home"));
        System.setProperty("jk.env.JK_HOME", home.toString());
        System.setProperty(
                MavenSettings.SETTINGS_PROPERTY,
                tmp.resolve("absent-settings.xml").toString());
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        RunNotices.clear();
        // The test framework jk adds to every Java project, so the fixed lock resolves from the stub alone.
        new MavenStub(http)
                .leaf(GROUP, ARTIFACT, version)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    @AfterEach
    void restore() {
        restore(MavenSettings.SETTINGS_PROPERTY, priorSettings);
        restore("jk.env.JK_HOME", priorHome);
    }

    private static void restore(String property, @Nullable String prior) {
        if (prior == null) System.clearProperty(property);
        else System.setProperty(property, prior);
    }

    @Test
    void a_config_toml_with_a_mirror_and_a_syntax_error_fails_the_lock_naming_the_file_and_dials_nothing()
            throws Exception {
        Path config = Path.of(System.getProperty("jk.env.JK_HOME")).resolve("config.toml");
        String mirror = """
                [repositories.corp]
                url = "%s"
                mirror-of = ["*"]
                """.formatted(http.baseUrl());
        Files.writeString(config, mirror + "\n[network\n");
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"
                java = 25

                [dependencies]
                lib = "%s:%s:%s"
                """.formatted(GROUP, ARTIFACT, version));

        LockFlow.Result broken = LockFlow.run(project, tmp.resolve("cache"), List.of(), false, null);

        assertThat(broken.status()).isNotZero();
        assertThat(broken.error()).contains(config + ":5:");
        assertThat(http.requested())
                .as("nothing is dialed, the mirror included")
                .isEmpty();

        Files.writeString(config, mirror);
        LockFlow.Result fixed = LockFlow.run(project, tmp.resolve("cache"), List.of(), false, null);

        assertThat(fixed.error()).isNull();
        assertThat(fixed.status()).isZero();
        assertThat(http.requested()).contains(MavenStub.path(GROUP, ARTIFACT, version, ".jar"));
    }
}
