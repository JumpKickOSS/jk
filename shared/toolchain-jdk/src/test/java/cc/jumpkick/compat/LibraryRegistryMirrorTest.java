// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.repo.LibraryRegistryClient;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The library registry's origin: the environment over {@code [mirrors] libraries} over a settings.xml mirror over the public file. */
class LibraryRegistryMirrorTest {

    @TempDir
    Path dir;

    @Test
    void without_a_mirror_the_registry_is_the_public_file() {
        LibraryRegistryMirror none = LibraryRegistryMirror.of(name -> null, Map.of(), MavenSettings.empty());

        assertThat(none.source()).isEqualTo(LibraryRegistryClient.DEFAULT_SOURCE);
        assertThat(LibraryRegistryClient.DEFAULT_SOURCE.getPath()).endsWith("/" + LibraryRegistryMirror.FILE);
    }

    @Test
    void a_mirror_serves_the_registry_file_under_its_directory_with_its_host_as_the_credential_id() {
        LibraryRegistryMirror mirror = LibraryRegistryMirror.of(
                name -> null, Map.of("libraries", "https://nexus.corp/repository/jk-libraries"), MavenSettings.empty());

        assertThat(mirror.source()).hasToString("https://nexus.corp/repository/jk-libraries/libraries.toml");
        assertThat(Objects.requireNonNull(mirror.origin()).credentialId()).isEqualTo("nexus.corp");
    }

    @Test
    void the_environment_wins_over_the_table_and_a_settings_mirror_naming_jk_libraries_comes_last() throws Exception {
        Path file = dir.resolve("settings.xml");
        Files.writeString(file, """
                <settings><mirrors>
                  <mirror><id>corp-libs</id><mirrorOf>jk-libraries</mirrorOf><url>https://artifacts.corp/libs/</url></mirror>
                  <mirror><id>corp-all</id><mirrorOf>*</mirrorOf><url>https://artifacts.corp/maven/</url></mirror>
                </mirrors></settings>
                """);
        MavenSettings settings = MavenSettings.loadFrom(file);
        Map<String, String> table = Map.of("libraries", "https://table.corp/libs/");
        Map<String, String> env = Map.of(LibraryRegistryMirror.ENV, "https://env.corp/libs");

        assertThat(LibraryRegistryMirror.of(env::get, table, settings).source())
                .hasToString("https://env.corp/libs/libraries.toml");
        assertThat(LibraryRegistryMirror.of(name -> null, table, settings).source())
                .hasToString("https://table.corp/libs/libraries.toml");
        assertThat(LibraryRegistryMirror.of(name -> null, Map.of(), settings).origin())
                .as(
                        "a settings.xml mirror naming jk-libraries, with its id as the credential id; never the wildcard one")
                .isEqualTo(new DownloadOrigin(URI.create("https://artifacts.corp/libs/"), "corp-libs"));
    }

    @Test
    void a_wildcard_maven_mirror_never_stands_in_for_the_registry() throws Exception {
        Path file = dir.resolve("settings.xml");
        Files.writeString(file, """
                <settings><mirrors>
                  <mirror><id>corp-all</id><mirrorOf>*</mirrorOf><url>https://artifacts.corp/maven/</url></mirror>
                </mirrors></settings>
                """);

        assertThat(LibraryRegistryMirror.of(name -> null, Map.of(), MavenSettings.loadFrom(file))
                        .source())
                .isEqualTo(LibraryRegistryClient.DEFAULT_SOURCE);
    }
}
