// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The repositories of the active {@code settings.xml} profiles join an imported manifest's
 * {@code [repositories]} beside the POM's own: a Maven shop reaches its Nexus through the
 * profile, not the POM, and the lock has to know the repository to consult it.
 */
class PomSettingsRepositoriesImportTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>widget</artifactId>
              <version>1.0.0</version>
              <repositories>
                <repository>
                  <id>corp-releases</id>
                  <url>https://pom.example/releases/</url>
                </repository>
              </repositories>
            </project>
            """;

    private static final String SETTINGS = """
            <settings>
              <profiles>
                <profile>
                  <id>corp</id>
                  <repositories>
                    <repository>
                      <id>corp-releases</id>
                      <url>https://settings.example/releases/</url>
                    </repository>
                    <repository>
                      <id>corp-snapshots</id>
                      <url>https://settings.example/snapshots/</url>
                      <releases><enabled>false</enabled></releases>
                    </repository>
                    <repository>
                      <id>central</id>
                      <url>https://repo.maven.apache.org/maven2/</url>
                    </repository>
                  </repositories>
                </profile>
                <profile>
                  <id>lab</id>
                  <repositories>
                    <repository><id>lab</id><url>https://lab.example/m2/</url></repository>
                  </repositories>
                </profile>
              </profiles>
              <activeProfiles><activeProfile>corp</activeProfile></activeProfiles>
            </settings>
            """;

    @Test
    void active_profile_repositories_join_after_the_poms_own_and_central_stays_implicit(@TempDir Path tmp)
            throws Exception {
        PomImporter.Result result = importer(tmp, SETTINGS).importFrom(pom(tmp));

        List<RepositorySpec> repos = result.jkBuild().repositories();
        assertThat(repos).extracting(RepositorySpec::name).containsExactly("corp-releases", "corp-snapshots");
        // The POM's declaration of a shared id wins over the profile's.
        assertThat(repos.getFirst().url()).hasToString("https://pom.example/releases/");
        RepositorySpec snapshots = repos.get(1);
        assertThat(snapshots.url()).hasToString("https://settings.example/snapshots/");
        assertThat(snapshots.releases()).isFalse();
        assertThat(snapshots.snapshots()).isTrue();
    }

    @Test
    void a_settings_file_without_active_profiles_adds_nothing(@TempDir Path tmp) throws Exception {
        PomImporter.Result result = importer(tmp, "<settings/>").importFrom(pom(tmp));

        assertThat(result.jkBuild().repositories())
                .extracting(RepositorySpec::name)
                .containsExactly("corp-releases");
    }

    private static Path pom(Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, POM, StandardCharsets.UTF_8);
        return pom;
    }

    private static PomImporter importer(Path tmp, String settingsXml) throws IOException {
        Path settings = tmp.resolve("settings.xml");
        Files.writeString(settings, settingsXml, StandardCharsets.UTF_8);
        Path empty = Files.createDirectories(tmp.resolve("no-repo"));
        Cas cas = new Cas(tmp.resolve("cache"));
        return new PomImporter(
                RepoGroup.of(new MavenRepo("fixture", empty.toUri(), new Http(), cas)),
                cas,
                uri -> {
                    throw new IOException("no remote files in this test: " + uri);
                },
                MavenSettings.loadFrom(settings));
    }
}
