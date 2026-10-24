// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M2BackingTest {

    private static MavenSettings settings(Path dir, String profileRepositoryUrl) throws Exception {
        Path xml = dir.resolve("settings.xml");
        Files.writeString(xml, """
                <settings>
                  <profiles>
                    <profile>
                      <id>p</id>
                      <repositories>
                        <repository><id>declared</id><url>%s</url></repository>
                      </repositories>
                    </profile>
                  </profiles>
                  <activeProfiles><activeProfile>p</activeProfile></activeProfiles>
                </settings>
                """.formatted(profileRepositoryUrl));
        return MavenSettings.loadFrom(xml);
    }

    @Test
    void central_is_shared_with_maven_whatever_settings_say(@TempDir Path dir) throws Exception {
        assertThat(M2Backing.shared(RepositorySpec.MAVEN_CENTRAL.url(), MavenSettings.empty()))
                .isTrue();
        assertThat(M2Backing.shared(RepositorySpec.MAVEN_CENTRAL.url(), settings(dir, "https://other.example/m2/")))
                .isTrue();
    }

    /** Google is a repository jk ships with, so it keeps its own tree until the user's settings.xml names it. */
    @Test
    void a_shipped_repository_moves_to_the_local_repository_only_when_settings_declare_it(@TempDir Path dir)
            throws Exception {
        URI google = RepositorySpec.GOOGLE_MAVEN.url();
        assertThat(M2Backing.shared(google, MavenSettings.empty())).isFalse();
        assertThat(M2Backing.shared(google, settings(dir, google.toString()))).isTrue();
    }

    @Test
    void an_undeclared_origin_keeps_its_own_tree(@TempDir Path dir) throws Exception {
        URI private1 = URI.create("https://private.example/maven/");
        assertThat(M2Backing.shared(private1, settings(dir, "https://declared.example/m2/")))
                .isFalse();
        assertThat(M2Backing.shared(private1, settings(dir, "https://private.example/maven/")))
                .as("a declared repository is matched on its canonical origin, so a trailing slash is not a difference")
                .isTrue();
    }
}
