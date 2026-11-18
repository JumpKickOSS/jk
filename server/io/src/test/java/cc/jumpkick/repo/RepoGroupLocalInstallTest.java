// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * This machine's installs in a repository group ({@link RepoGroup#withLocalInstalls}): a version a
 * resolve names that no repository lists is taken from them, a snapshot is fetched from them first,
 * a release last, and a floating ask or a claimed group never reaches them.
 */
class RepoGroupLocalInstallTest {

    /** A Maven local repository of each test's own: what one test installs, the next never sees. */
    @BeforeEach
    void isolateM2(@TempDir Path m2) {
        System.setProperty(M2Dirs.LOCAL_PROPERTY, m2.toString());
    }

    @BeforeEach
    @AfterEach
    void reset() {
        SessionContext.reset();
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    private static final String SNAPSHOT = "0.1.0-SNAPSHOT";

    private static Coordinate lib(String version) {
        return Coordinate.of("zz.local", "liba", version);
    }

    /** {@code version}'s POM and jar in the Maven-layout directory {@code repo}; the jar says {@code body}. */
    private static void install(Path repo, String version, String body) throws IOException {
        for (String rel : List.of(MavenLayout.pomPath(lib(version)), MavenLayout.artifactPath(lib(version)))) {
            Path file = repo.resolve(rel);
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    rel.endsWith(".pom")
                            ? "<project><modelVersion>4.0.0</modelVersion><groupId>zz.local</groupId>"
                                    + "<artifactId>liba</artifactId><version>" + version + "</version></project>"
                            : body,
                    StandardCharsets.UTF_8);
        }
    }

    private record Fixture(Path dir, Cas cas, Path m2, Path shelf, Path remote) {
        MavenRepo repo(String name, Path at, boolean releases, boolean snapshots) {
            return new MavenRepo(name, at.toUri(), new Http(), cas).withPolicy(releases, snapshots);
        }

        List<MavenRepo> installs() {
            return List.of(
                    repo(RepositorySpec.LOCAL_INSTALL, m2, true, true),
                    repo(RepositorySpec.LOCAL_INSTALL, shelf, true, true));
        }

        /** A releases-only remote, as Central is, then the installs. */
        RepoGroup group() {
            return RepoGroup.of(repo("remote", remote, true, false)).withLocalInstalls(installs());
        }
    }

    private static Fixture fixture(Path dir) throws IOException {
        Path m2 = M2Dirs.localRepository();
        Path cas = dir.resolve("store");
        Path shelf = cas.resolve("repos").resolve(RepositorySpec.JK_LOCAL);
        Path remote = dir.resolve("remote");
        Files.createDirectories(shelf);
        Files.createDirectories(remote);
        return new Fixture(dir, new Cas(cas), m2, shelf, remote);
    }

    @Test
    void a_snapshot_named_by_a_pin_resolves_from_either_install(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        install(f.shelf(), SNAPSHOT, "shelf");
        RepoGroup group = f.group();

        assertThat(group.availableVersions(lib("any"), Set.of(SNAPSHOT), true)).contains(SNAPSHOT);
        RepoGroup.RepoFetched pom = group.tryFetchPom(lib(SNAPSHOT)).orElseThrow();
        assertThat(group.isLocalInstall(pom.repo())).isTrue();
        RepoGroup.RepoFetched jar = group.tryFetchArtifact(lib(SNAPSHOT)).orElseThrow();
        assertThat(Files.readString(jar.fetched().cachePath())).isEqualTo("shelf");
    }

    @Test
    void a_floating_ask_never_lands_on_an_install(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        install(f.remote(), "1.0.0", "remote");
        install(f.m2(), "2.0.0", "installed release");
        install(f.m2(), SNAPSHOT, "installed snapshot");
        RepoGroup group = f.group();

        assertThat(group.availableVersions(lib("any"))).containsExactly("1.0.0");
        assertThat(group.availableVersions(lib("any"), Set.of(), true)).containsExactly("1.0.0");
        // Named, the installed release is a candidate.
        assertThat(group.availableVersions(lib("any"), Set.of("2.0.0"), false)).contains("1.0.0", "2.0.0");
    }

    @Test
    void a_release_a_repository_serves_comes_from_it_and_an_installed_one_only_when_none_does(@TempDir Path dir)
            throws Exception {
        Fixture f = fixture(dir);
        install(f.remote(), "1.0.0", "remote");
        install(f.m2(), "1.0.0", "installed over a published release");
        install(f.m2(), "1.1.0", "installed only");
        RepoGroup group = f.group();

        RepoGroup.RepoFetched published = group.tryFetchArtifact(lib("1.0.0")).orElseThrow();
        assertThat(group.isLocalInstall(published.repo())).isFalse();
        assertThat(Files.readString(published.fetched().cachePath())).isEqualTo("remote");
        RepoGroup.RepoFetched installed = group.tryFetchArtifact(lib("1.1.0")).orElseThrow();
        assertThat(group.isLocalInstall(installed.repo())).isTrue();
    }

    @Test
    void a_snapshot_is_fetched_from_the_install_ahead_of_a_snapshot_repository(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        install(f.remote(), SNAPSHOT, "deployed");
        install(f.m2(), SNAPSHOT, "installed here");
        RepoGroup group =
                RepoGroup.of(f.repo("snapshots", f.remote(), true, true)).withLocalInstalls(f.installs());

        RepoGroup.RepoFetched jar = group.tryFetchArtifact(lib(SNAPSHOT)).orElseThrow();
        assertThat(group.isLocalInstall(jar.repo())).isTrue();
        assertThat(Files.readString(jar.fetched().cachePath())).isEqualTo("installed here");
    }

    /** Offline, a {@code file:} repository lists its store copy too; a removed install's copy is not an install. */
    @Test
    void an_install_removed_from_the_machine_is_not_offered_from_its_copy_offline(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        install(f.m2(), SNAPSHOT, "installed");
        RepoGroup group = f.group();
        assertThat(group.tryFetchArtifact(lib(SNAPSHOT))).isPresent();

        PathUtil.deleteRecursivelyOrThrow(f.m2().resolve("zz"));
        SessionContext.installConfig(JkConfig.empty().withOffline(true));

        assertThat(group.availableVersions(lib("any"), Set.of(SNAPSHOT), true)).doesNotContain(SNAPSHOT);
    }

    @Test
    void a_group_a_repository_claims_never_reaches_the_installs(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        install(f.m2(), SNAPSHOT, "installed");
        RepoGroup group = new RepoGroup(List.of(f.repo("corp", f.remote(), true, true)), List.of(List.of("zz.local")))
                .withLocalInstalls(f.installs());

        assertThat(group.availableVersions(lib("any"), Set.of(SNAPSHOT), true)).isEmpty();
        assertThat(group.tryFetchPom(lib(SNAPSHOT))).isEmpty();
    }

    @Test
    void a_repository_already_at_an_install_answers_for_it_as_declared(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        RepoGroup group = RepoGroup.of(f.repo("m2", f.m2(), true, true)).withLocalInstalls(f.installs());

        assertThat(group.localInstalls()).hasSize(1);
        assertThat(group.repos()).hasSize(2);
    }

    @Test
    void a_dependencys_repository_goes_ahead_of_the_installs(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        MavenRepo declared = f.repo("declared", dir.resolve("declared"), true, true);
        RepoGroup group = f.group().withReposAppended(List.of(declared));

        assertThat(group.repos().get(1)).isSameAs(declared);
        assertThat(group.localInstalls()).hasSize(2).noneMatch(r -> r == declared);
        assertThat(group.isLocalInstall(group.repos().getLast())).isTrue();
    }
}
