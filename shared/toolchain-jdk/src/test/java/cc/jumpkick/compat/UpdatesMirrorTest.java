// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.updates.UpdateManifest;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The update manifest's origin: the environment over {@code [mirrors] updates} over a settings.xml mirror over the public copies. */
class UpdatesMirrorTest {

    @TempDir
    Path dir;

    @Test
    void without_a_mirror_jumpkick_build_is_asked_first_and_github_second() {
        UpdatesMirror none = UpdatesMirror.of(name -> null, Map.of(), MavenSettings.empty());

        assertThat(none.sources())
                .extracting(URI::getHost)
                .containsExactly("jumpkick.build", "raw.githubusercontent.com");
        assertThat(none.sources()).allSatisfy(uri -> {
            assertThat(uri.getPath()).endsWith("/" + UpdateManifest.FILE);
            assertThat(uri.getQuery()).isNull();
        });
    }

    @Test
    void a_mirror_is_the_only_source_and_serves_the_file_under_its_directory() {
        UpdatesMirror mirror = UpdatesMirror.of(
                name -> null, Map.of("updates", "https://nexus.corp/repository/jk-updates"), MavenSettings.empty());

        assertThat(mirror.sources())
                .containsExactly(URI.create("https://nexus.corp/repository/jk-updates/updates.json"));
        assertThat(Objects.requireNonNull(mirror.origin()).credentialId()).isEqualTo("nexus.corp");
    }

    @Test
    void the_environment_wins_over_the_table_and_a_settings_mirror_naming_jk_updates_comes_last() throws Exception {
        Path file = dir.resolve("settings.xml");
        Files.writeString(file, """
                <settings><mirrors>
                  <mirror><id>corp-updates</id><mirrorOf>jk-updates</mirrorOf><url>https://artifacts.corp/updates/</url></mirror>
                  <mirror><id>corp-all</id><mirrorOf>*</mirrorOf><url>https://artifacts.corp/maven/</url></mirror>
                </mirrors></settings>
                """);
        MavenSettings settings = MavenSettings.loadFrom(file);
        Map<String, String> table = Map.of("updates", "https://table.corp/updates/");
        Map<String, String> env = Map.of(UpdatesMirror.ENV, "https://env.corp/updates");

        assertThat(UpdatesMirror.of(env::get, table, settings).sources())
                .containsExactly(URI.create("https://env.corp/updates/updates.json"));
        assertThat(UpdatesMirror.of(name -> null, table, settings).sources())
                .containsExactly(URI.create("https://table.corp/updates/updates.json"));
        assertThat(UpdatesMirror.of(name -> null, Map.of(), settings).origin())
                .isEqualTo(new DownloadOrigin(URI.create("https://artifacts.corp/updates/"), "corp-updates"));
    }
}
