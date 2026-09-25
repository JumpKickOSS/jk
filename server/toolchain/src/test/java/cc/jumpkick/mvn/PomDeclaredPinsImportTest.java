// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.ProjectImport;
import cc.jumpkick.gradle.GradleBuildImport;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.DeadEndpoint;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** A version the POM pins that no repository lists is a row at import, and an unreachable repository is a note. */
class PomDeclaredPinsImportTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

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
                GradleBuildImport.scannerOnly(),
                pom,
                pom.resolveSibling("jk.toml"),
                pom.getParent(),
                null,
                true,
                report,
                note -> {});

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
                    .filteredOn(i -> i.message().startsWith("`net.java.dev.swing-layout:swing-layout 1.0.2`"))
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

    private static final String REACTOR = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>reactor</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
              <modules><module>a</module><module>b</module></modules>
            </project>
            """;

    private static final String MEMBER = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>reactor</artifactId>
                <version>1.0</version>
              </parent>
              <artifactId>%s</artifactId>
              <dependencies>
                <dependency>
                  <groupId>net.java.dev.swing-layout</groupId>
                  <artifactId>swing-layout</artifactId>
                  <version>%s</version>
                </dependency>
              </dependencies>
            </project>
            """;

    /**
     * Two modules pinning one coordinate at two versions read its catalog once: the walk is per
     * {@code group:artifact}, and every version the reactor pins is judged against that one read.
     */
    @Test
    void one_coordinate_pinned_at_two_versions_reads_its_catalog_once(@TempDir Path tmp) throws Exception {
        upstream.metadata("net.java.dev.swing-layout", "swing-layout", "1.0", "1.0.1");
        Path pom = writeReactor(tmp, "1.0.1", "1.0.2");
        PomImporter importer = TestImporters.over(tmp, http.base());

        PomImporter.WorkspaceImportResult result = importer.importWorkspace(pom);
        ImportReport checked = DeclaredPins.check(result.root(), result.modules(), result.report(), importer);

        assertThat(http.requestsFor(MavenStub.metadataPath("net.java.dev.swing-layout", "swing-layout")))
                .isEqualTo(1);
        assertThat(checked.issues())
                .filteredOn(i -> i.message().contains("swing-layout"))
                .singleElement()
                .satisfies(i -> assertThat(i.message())
                        .startsWith("[b] `net.java.dev.swing-layout:swing-layout 1.0.2` is pinned by the POM")
                        .contains("`jk lock` refuses it"));
    }

    /**
     * A sweep that runs out of its budget claims nothing about the pins it did not reach: they are
     * one Tier-2 note with the count, and {@code jk lock} judges them. Nothing is walked under a
     * budget of zero, so the note covers every pin.
     */
    @Test
    void pins_the_budget_leaves_unchecked_are_one_note_not_rows(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        writeMeta(repo, "net.java.dev.swing-layout", "swing-layout", "1.0", "1.0.1");
        writeMeta(repo, "junit", "junit", "4.13.2");
        Path pom = writePom(tmp);
        PomImporter importer = TestImporters.over(tmp, repo.toUri());

        PomImporter.WorkspaceImportResult result = importer.importWorkspace(pom);
        ImportReport checked = DeclaredPins.check(
                result.root(),
                result.modules(),
                result.report(),
                DeclaredPins.lockRepos(importer.resolver.repos(), result.root(), importer.resolver.cas()),
                Clock.SYSTEM,
                Duration.ZERO);

        assertThat(checked.hasErrors()).isFalse();
        assertThat(checked.issues())
                .filteredOn(i -> i.message().contains("were not checked"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.severity()).isEqualTo(ImportReport.Severity.WARNING);
                    assertThat(i.message())
                            .startsWith("2 pinned versions (2 coordinates) were not checked against the repositories")
                            .contains("0-second budget after 0 of 2")
                            .contains("`jk lock` decides whether each version exists");
                });
        assertThat(checked.issues()).noneMatch(i -> i.message().contains("is pinned by the POM"));
    }

    /**
     * A walk in flight when the budget runs out asks no further repository, and a version the
     * repositories it did ask do not list is unchecked rather than refused: the one it never asked
     * may list it. The clock here advances four nanoseconds per reading under a ten-nanosecond
     * budget, so the first repository is asked and the second is not.
     */
    @Test
    void a_walk_the_budget_cuts_short_claims_nothing_about_the_repositories_it_did_not_ask(@TempDir Path tmp)
            throws Exception {
        Path repo = tmp.resolve("repo");
        writeMeta(repo, "net.java.dev.swing-layout", "swing-layout", "1.0", "1.0.1", "1.0.2");
        Path corp = tmp.resolve("corp");
        writeMeta(corp, "net.java.dev.swing-layout", "swing-layout", "1.0");
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(
                pom,
                POM.replace(
                                "<dependencies>",
                                "<repositories><repository><id>corp</id><url>" + corp.toUri()
                                        + "</url></repository></repositories><dependencies>")
                        .replaceAll("(?s)<dependency>\\s*<groupId>junit</groupId>.*?</dependency>", ""));
        PomImporter importer = TestImporters.over(tmp, repo.toUri());
        Clock stepping = new Clock() {
            private long reading;

            @Override
            public long millis() {
                return 0;
            }

            @Override
            public synchronized long nanos() {
                long now = reading;
                reading += 4;
                return now;
            }
        };

        PomImporter.WorkspaceImportResult result = importer.importWorkspace(pom);
        ImportReport checked = DeclaredPins.check(
                result.root(),
                result.modules(),
                result.report(),
                DeclaredPins.lockRepos(importer.resolver.repos(), result.root(), importer.resolver.cas()),
                stepping,
                Duration.ofNanos(10));

        assertThat(checked.hasErrors()).isFalse();
        assertThat(checked.issues()).noneMatch(i -> i.message().contains("is pinned by the POM"));
        assertThat(checked.issues())
                .filteredOn(i -> i.message().contains("were not checked"))
                .singleElement()
                .satisfies(i -> assertThat(i.message())
                        .startsWith("1 pinned version (1 coordinate) were not checked")
                        .contains("after 0 of 1"));
    }

    /**
     * A repository that refuses — a 401 to every request, a rate limit's 429 — is asked once per
     * import: the first walk to reach it records the answer, and every other walk notes the
     * repository without a request, so a refusing github.com-hosted repository costs one round
     * trip rather than one per pinned coordinate.
     */
    @Test
    void a_repository_that_refuses_is_asked_once_per_import(@TempDir Path tmp) throws Exception {
        AtomicInteger asked = new AtomicInteger();
        HttpServer refusing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        refusing.createContext("/", exchange -> {
            asked.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        refusing.start();
        try {
            Path repo = tmp.resolve("repo");
            writeMeta(repo, "net.java.dev.swing-layout", "swing-layout", "1.0.2");
            URI corp = URI.create("http://127.0.0.1:" + refusing.getAddress().getPort() + "/");
            Path project = Files.createDirectories(tmp.resolve("project"));
            Path pom = project.resolve("pom.xml");
            Files.writeString(
                    pom,
                    POM.replace(
                            "<dependencies>",
                            "<repositories><repository><id>corp</id><url>" + corp
                                    + "</url></repository></repositories><dependencies>"));
            PomImporter importer = TestImporters.over(tmp, repo.toUri());

            PomImporter.WorkspaceImportResult result = importer.importWorkspace(pom);
            ImportReport checked = DeclaredPins.check(result.root(), result.modules(), result.report(), importer);

            assertThat(asked.get())
                    .as("requests the refusing repository received")
                    .isEqualTo(1);
            assertThat(checked.issues())
                    .filteredOn(i -> i.message().contains("junit:junit"))
                    .singleElement()
                    .satisfies(i -> assertThat(i.message())
                            .as("whichever walk dials, the row names the 401; the other does not ask")
                            .contains("was not checked against the repositories the lock reads")
                            .contains("HTTP 401"));
            assertThat(checked.issues())
                    .as("the pin the fixture lists has no row")
                    .noneMatch(i -> i.message().startsWith("`net.java.dev.swing-layout"));
        } finally {
            refusing.stop(0);
        }
    }

    private static Path writeReactor(Path tmp, String versionA, String versionB) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, REACTOR);
        Files.createDirectories(project.resolve("a"));
        Files.writeString(project.resolve("a/pom.xml"), MEMBER.formatted("a", versionA));
        Files.createDirectories(project.resolve("b"));
        Files.writeString(project.resolve("b/pom.xml"), MEMBER.formatted("b", versionB));
        return pom;
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
