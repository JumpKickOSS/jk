// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.ProjectImport;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.DeadEndpoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A version the POM pins that no repository lists is a row at import, and an unreachable repository is a note. */
class PomDeclaredPinsImportTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>mvp</artifactId>
              <version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>net.java.dev.swing-layout</groupId>
                  <artifactId>swing-layout</artifactId>
                  <version>1.0.2</version>
                </dependency>
                <dependency>
                  <groupId>junit</groupId>
                  <artifactId>junit</artifactId>
                  <version>4.13.2</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void a_pin_no_repository_lists_is_a_tier3_row_naming_what_the_catalog_lists(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        writeMeta(repo, "net.java.dev.swing-layout", "swing-layout", "1.0", "1.0.1");
        writeMeta(repo, "junit", "junit", "4.12", "4.13.2");
        Path pom = writePom(tmp);
        PomImporter importer = TestImporters.over(tmp, repo.toUri());

        PomImporter.WorkspaceImportResult result = importer.importWorkspace(pom);
        ImportReport checked = DeclaredPins.check(result.root(), result.modules(), result.report(), importer);

        List<String> errors = checked.issues().stream()
                .filter(i -> i.severity() == ImportReport.Severity.ERROR)
                .map(ImportReport.Issue::message)
                .toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0))
                .startsWith("`net.java.dev.swing-layout:swing-layout 1.0.2` is pinned by the POM")
                .contains("fixture lists 1.0.1, 1.0")
                .contains("`jk lock` refuses it");
        assertThat(checked.issues()).noneMatch(i -> i.message().contains("junit:junit"));
        assertThat(checked.issues().size()).isEqualTo(result.report().issues().size() + 1);
    }

    @Test
    void a_pin_whose_pom_the_store_already_mirrors_is_not_walked(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        writeMeta(repo, "net.java.dev.swing-layout", "swing-layout", "1.0", "1.0.1");
        writeMeta(repo, "junit", "junit", "4.13.2");
        Path pom = writePom(tmp);
        PomImporter importer = TestImporters.over(tmp, repo.toUri());
        // A lock of this machine fetched swing-layout 1.0.2 from the fixture repository before: its
        // POM sits in the repository's store, which is proof enough that the version exists.
        Path mirrored = Files.writeString(tmp.resolve("swing-layout-1.0.2.pom"), "<project/>");
        RepoArtifactStore.forRepository(importer.resolver.cas().root(), "fixture", repo.toUri())
                .materialize(
                        MavenLayout.pomPath(Coordinate.ofModule("net.java.dev.swing-layout:swing-layout", "1.0.2")),
                        mirrored,
                        Hashing.sha256Hex(mirrored));

        PomImporter.WorkspaceImportResult result = importer.importWorkspace(pom);
        ImportReport checked = DeclaredPins.check(result.root(), result.modules(), result.report(), importer);

        assertThat(checked.issues())
                .as("the catalog does not list 1.0.2, and it was never asked")
                .noneMatch(i -> i.message().contains("swing-layout"));
    }

    @Test
    void the_import_command_writes_the_row_into_the_report(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        writeMeta(repo, "net.java.dev.swing-layout", "swing-layout", "1.0", "1.0.1");
        writeMeta(repo, "junit", "junit", "4.13.2");
        Path pom = writePom(tmp);
        Path report = tmp.resolve("report.md");

        ProjectImport.Outcome outcome = ProjectImport.run(
                TestImporters.over(tmp, repo.toUri()),
                pom,
                pom.resolveSibling("jk.toml"),
                pom.getParent(),
                null,
                true,
                report);

        assertThat(outcome.exit()).isZero();
        assertThat(Files.readString(report))
                .contains("## Tier 3")
                .contains("`net.java.dev.swing-layout:swing-layout 1.0.2` is pinned by the POM");
        assertThat(Files.readString(pom.resolveSibling("jk.toml")))
                .as("the pin is still written; the row is the diagnosis, not a rewrite")
                .contains("swing-layout = \"net.java.dev.swing-layout:swing-layout:1.0.2\"");
    }

    @Test
    void a_repository_that_cannot_be_reached_is_a_note_not_a_claim(@TempDir Path tmp) throws Exception {
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            Cas cas = new Cas(tmp.resolve("cache"));
            MavenRepo deadRepo = new MavenRepo("dead", dead.uri(), Http.failFast(), cas);
            PomImporter importer = new PomImporter(RepoGroup.of(deadRepo), cas, uri -> {
                throw new IOException("no remote files in this test: " + uri);
            });
            Path pom = writePom(tmp);

            PomImporter.WorkspaceImportResult result = importer.importWorkspace(pom);
            ImportReport checked = DeclaredPins.check(result.root(), result.modules(), result.report(), importer);

            assertThat(checked.issues())
                    .filteredOn(i -> i.message().contains("swing-layout"))
                    .singleElement()
                    .satisfies(i -> {
                        assertThat(i.severity()).isEqualTo(ImportReport.Severity.WARNING);
                        assertThat(i.message())
                                .contains("was not checked against the repositories the lock reads")
                                .contains("dead could not be reached");
                    });
            assertThat(checked.hasErrors()).isFalse();
        }
    }

    @Test
    void a_repository_the_pom_declares_counts_as_one_the_lock_reads(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        writeMeta(repo, "junit", "junit", "4.13.2");
        Path corp = tmp.resolve("corp");
        writeMeta(corp, "net.java.dev.swing-layout", "swing-layout", "1.0.2");
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(
                pom,
                POM.replace(
                        "<dependencies>",
                        "<repositories><repository><id>corp</id><url>" + corp.toUri()
                                + "</url></repository></repositories><dependencies>"));
        PomImporter importer = TestImporters.over(tmp, repo.toUri());

        PomImporter.WorkspaceImportResult result = importer.importWorkspace(pom);
        ImportReport checked = DeclaredPins.check(result.root(), result.modules(), result.report(), importer);

        RepoGroup lockRepos = DeclaredPins.lockRepos(importer.resolver.repos(), result.root(), importer.resolver.cas());
        assertThat(lockRepos.repos()).extracting(MavenRepo::name).containsExactly("corp", "fixture");
        assertThat(checked.issues()).noneMatch(i -> i.message().contains("swing-layout"));
    }

    @Test
    void only_exact_remote_release_pins_are_checked() {
        assertThat(DeclaredPins.exactRemoteVersion(Dependency.of("lib", "g:a", VersionSelector.parse("1.0.2"))))
                .isEqualTo("1.0.2");
        assertThat(DeclaredPins.exactRemoteVersion(Dependency.of("lib", "g:a", VersionSelector.parse("^1.0"))))
                .isNull();
        assertThat(DeclaredPins.exactRemoteVersion(Dependency.of("lib", "g:a", VersionSelector.parse("1.0-SNAPSHOT"))))
                .isNull();
        assertThat(DeclaredPins.exactRemoteVersion(
                        Dependency.of("lib", "g:a", VersionSelector.parse(DependencyMapping.UNRESOLVED))))
                .isNull();
        assertThat(DeclaredPins.exactRemoteVersion(Dependency.workspace("core")))
                .isNull();
        assertThat(DeclaredPins.exactRemoteVersion(Dependency.platformManaged("lib", "g:a")))
                .isNull();
    }

    private static Path writePom(Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, POM);
        return pom;
    }

    private static void writeMeta(Path repoRoot, String group, String artifact, String... versions) throws IOException {
        Path meta = repoRoot.resolve(group.replace('.', '/')).resolve(artifact).resolve("maven-metadata.xml");
        Files.createDirectories(meta.getParent());
        StringBuilder xml = new StringBuilder();
        xml.append("<metadata><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) xml.append("<version>").append(v).append("</version>");
        xml.append("</versions></versioning></metadata>");
        Files.writeString(meta, xml.toString());
    }
}
