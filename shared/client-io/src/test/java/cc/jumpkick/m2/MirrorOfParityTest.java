// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.m2;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.MirrorOf;
import cc.jumpkick.model.RepositoryMirror;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One table, both sources: a {@code settings.xml} {@code <mirrorOf>} string and a {@code
 * ~/.jk/config.toml} {@code mirror-of} list with the same tokens answer every row alike.
 */
class MirrorOfParityTest {

    private record Row(List<String> tokens, String repoId, String repoUrl, boolean mirrored) {}

    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final String LOCAL = "http://localhost:8081/repository/internal/";
    private static final String PLAIN = "http://repo.acme.test/maven/";
    private static final String DISK = "file:///srv/m2/";

    private static final List<Row> TABLE = List.of(
            new Row(List.of("*"), "central", CENTRAL, true),
            new Row(List.of("*"), "local", LOCAL, true),
            new Row(List.of("central"), "central", CENTRAL, true),
            new Row(List.of("central"), "google", CENTRAL, false),
            new Row(List.of("central", "google"), "google", CENTRAL, true),
            new Row(List.of("*", "!jumpkick"), "jumpkick", CENTRAL, false),
            new Row(List.of("*", "!jumpkick"), "central", CENTRAL, true),
            new Row(List.of("!jumpkick", "*"), "jumpkick", CENTRAL, false),
            new Row(List.of("external:*"), "central", CENTRAL, true),
            new Row(List.of("external:*"), "local", LOCAL, false),
            new Row(List.of("external:*"), "disk", DISK, false),
            new Row(List.of("external:http:*"), "central", CENTRAL, false),
            new Row(List.of("external:http:*"), "plain", PLAIN, true),
            new Row(List.of("external:http:*"), "local", LOCAL, false),
            new Row(List.of("external:*", "!central"), "central", CENTRAL, false),
            new Row(List.of("!"), "central", CENTRAL, false));

    @Test
    void a_settings_xml_mirror_and_a_config_toml_mirror_match_the_same_repositories() {
        Path file = Path.of("/home/me/.m2/settings.xml");
        for (Row row : TABLE) {
            URI url = URI.create(row.repoUrl());
            MavenSettings.Mirror maven =
                    new MavenSettings.Mirror("m", String.join(",", row.tokens()), URI.create(CENTRAL), file);
            RepositoryMirror jk =
                    new RepositoryMirror("m", URI.create(CENTRAL), new MirrorOf(row.tokens()), null, false, file);

            assertThat(maven.matches(row.repoId(), url))
                    .as("settings.xml %s", row)
                    .isEqualTo(row.mirrored());
            assertThat(jk.matches(row.repoId(), url)).as("config.toml %s", row).isEqualTo(row.mirrored());
        }
    }

    @Test
    void the_comma_spelling_and_the_list_are_one_matcher() {
        assertThat(MirrorOf.parse(" *, !jumpkick ,")).isEqualTo(new MirrorOf(List.of("*", "!jumpkick")));
        assertThat(new MirrorOf(List.of("*", "!jumpkick")).spelled()).isEqualTo("*,!jumpkick");
    }
}
