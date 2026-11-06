// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.RequestShell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The full precedence ladder of {@link M2Dirs#localRepository()}: {@code jk.m2.local} &gt;
 * {@code JK_M2_LOCAL} &gt; {@code maven.repo.local} &gt; {@code settings.xml} &gt;
 * {@code ~/.m2/repository}.
 *
 * <p>{@code JK_M2_LOCAL} is passed in rather than read from the process environment: the build
 * sets it on every test JVM (so a fixture can never scribble on the developer's real
 * {@code ~/.m2}), which means the four steps below it are unreachable from
 * {@link M2Dirs#localRepository()} under the harness.
 */
class M2DirsTest {

    @AfterEach
    void clear() {
        System.clearProperty("maven.repo.local");
    }

    @Test
    void jk_m2_local_property_outranks_every_other_source(@TempDir Path dir) throws Exception {
        Path custom = dir.resolve("custom-m2");
        System.setProperty("jk.m2.local", custom.toString());
        System.setProperty("maven.repo.local", dir.resolve("maven-local").toString());
        System.setProperty(
                "jk.m2.settings", settingsXml(dir, dir.resolve("from-settings")).toString());

        assertThat(M2Dirs.localRepository()).isEqualTo(custom);
        assertThat(M2Dirs.localRepository(dir.resolve("from-env").toString())).isEqualTo(custom);
    }

    @Test
    void jk_m2_local_env_outranks_maven_repo_local(@TempDir Path dir) {
        Path fromEnv = dir.resolve("from-env");
        System.setProperty("maven.repo.local", dir.resolve("maven-local").toString());

        assertThat(M2Dirs.localRepository(fromEnv.toString())).isEqualTo(fromEnv);
        // Blank is "unset": it must fall through rather than resolve to the empty path.
        assertThat(M2Dirs.localRepository("   ")).isEqualTo(dir.resolve("maven-local"));
    }

    @Test
    void maven_repo_local_is_used_when_no_jk_override(@TempDir Path dir) {
        Path maven = dir.resolve("maven-local");
        System.setProperty("maven.repo.local", maven.toString());

        assertThat(M2Dirs.localRepository(null)).isEqualTo(maven);
    }

    @Test
    void settings_xml_local_repository_is_used_when_no_override(@TempDir Path dir) throws Exception {
        Path custom = dir.resolve("from-settings");
        System.setProperty("jk.m2.settings", settingsXml(dir, custom).toString());

        assertThat(M2Dirs.localRepository(null)).isEqualTo(custom);
    }

    @Test
    void a_user_home_expression_in_local_repository_expands(@TempDir Path dir) throws Exception {
        Path settings = dir.resolve("settings.xml");
        Files.writeString(
                settings, "<settings><localRepository>${user.home}/m2-from-settings</localRepository></settings>");
        System.setProperty("jk.m2.settings", settings.toString());

        assertThat(M2Dirs.localRepository(null))
                .isEqualTo(Path.of(System.getProperty("user.home") + "/m2-from-settings"));
    }

    @Test
    void an_env_expression_in_local_repository_reads_the_requesting_shell(@TempDir Path dir) throws Exception {
        Path settings = dir.resolve("settings.xml");
        Files.writeString(
                settings, "<settings><localRepository>${env.M2_ROOT_FOR_TEST}/repo</localRepository></settings>");
        System.setProperty("jk.m2.settings", settings.toString());

        Path seen = RequestShell.where(Map.of("M2_ROOT_FOR_TEST", dir.toString()), () -> M2Dirs.localRepository(null));

        assertThat(seen).isEqualTo(dir.resolve("repo"));
    }

    @Test
    void an_unset_variable_in_local_repository_falls_back_to_the_default(@TempDir Path dir) throws Exception {
        Path settings = dir.resolve("settings.xml");
        Files.writeString(
                settings, "<settings><localRepository>${env.M2_ROOT_FOR_TEST}/repo</localRepository></settings>");
        System.setProperty("jk.m2.settings", settings.toString());

        Path seen = RequestShell.where(Map.of("SOMETHING_ELSE", "x"), () -> M2Dirs.localRepository(null));

        assertThat(seen).isEqualTo(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    @Test
    void an_unparseable_settings_xml_falls_back_to_the_default(@TempDir Path dir) throws Exception {
        Path settings = dir.resolve("settings.xml");
        Files.writeString(settings, "<settings><localRepository>oops");
        System.setProperty("jk.m2.settings", settings.toString());

        assertThat(M2Dirs.localRepository(null))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    @Test
    void the_default_is_the_home_m2_repository_when_nothing_is_set(@TempDir Path dir) {
        System.setProperty("jk.m2.settings", dir.resolve("absent-settings.xml").toString());

        assertThat(M2Dirs.localRepository(null))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    private static Path settingsXml(Path dir, Path localRepository) throws Exception {
        Path settings = dir.resolve("settings.xml");
        Files.writeString(settings, "<settings><localRepository>" + localRepository + "</localRepository></settings>");
        return settings;
    }
}
