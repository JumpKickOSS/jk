// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.SysProps;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk import} checks a pin against the repositories the lock will read, a repository the POM
 * declares among them; that one is asked through the mirror {@code ~/.jk/config.toml} puts in
 * front of it, as the lock asks it, never at its own URL.
 */
@ExtendWith(SysProps.class)
class DeclaredPinsMirrorTest {

    /** The repository the POM declares, at its own URL. */
    @RegisterExtension
    final LoopbackHttp origin = new LoopbackHttp();

    /** What a {@code mirror-of} table routes that repository to. */
    @RegisterExtension
    final LoopbackHttp mirror = new LoopbackHttp();

    @Test
    void a_pom_declared_repository_is_checked_through_its_mirror(@TempDir Path tmp) throws Exception {
        for (LoopbackHttp http : new LoopbackHttp[] {origin, mirror}) {
            new MavenStub(http).metadata("net.java.dev.swing-layout", "swing-layout", "1.0.2");
        }
        Path repo = tmp.resolve("repo");
        Path junit = repo.resolve("junit/junit/maven-metadata.xml");
        Files.createDirectories(junit.getParent());
        Files.writeString(junit, """
                <metadata><groupId>junit</groupId><artifactId>junit</artifactId><versioning><versions>\
                <version>4.13.2</version></versions></versioning></metadata>""");
        Path home = Files.createDirectories(tmp.resolve("home"));
        Files.writeString(home.resolve("config.toml"), """
                [repositories.corp-mirror]
                url = "%s"
                mirror-of = ["corp"]
                """.formatted(mirror.baseUrl()));
        System.setProperty("jk.env.JK_HOME", home.toString());
        System.setProperty(
                MavenSettings.SETTINGS_PROPERTY,
                tmp.resolve("absent-settings.xml").toString());
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>mvp</artifactId>
                  <version>1.0</version>
                  <repositories><repository><id>corp</id><url>%s</url></repository></repositories>
                  <dependencies>
                    <dependency>
                      <groupId>net.java.dev.swing-layout</groupId>
                      <artifactId>swing-layout</artifactId>
                      <version>1.0.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(origin.baseUrl()));
        PomImporter importer = TestImporters.over(tmp, repo.toUri());

        PomImporter.WorkspaceImportResult result = importer.importWorkspace(pom);
        ImportReport checked = DeclaredPins.check(result.root(), result.modules(), result.report(), importer);

        assertThat(checked.issues()).noneMatch(i -> i.message().contains("swing-layout"));
        assertThat(origin.requested())
                .as("the mirrored repository is never asked at its own URL")
                .isEmpty();
        assertThat(mirror.requested()).contains(MavenStub.metadataPath("net.java.dev.swing-layout", "swing-layout"));
        RepoGroup lockRepos = DeclaredPins.lockRepos(importer.resolver.repos(), result.root(), importer.resolver.cas());
        assertThat(lockRepos.repos().getFirst().mirror())
                .map(MavenRepo.Mirror::id)
                .contains("corp-mirror");
    }
}
