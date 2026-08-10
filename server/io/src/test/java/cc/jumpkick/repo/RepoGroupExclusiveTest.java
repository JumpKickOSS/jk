// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**exclusive groups hide versions/fetches from non-claiming repos. */
class RepoGroupExclusiveTest {

    @Test
    void exclusive_group_ignores_higher_version_on_public_repo(@TempDir Path tmp) throws Exception {
        Path internalDir = tmp.resolve("internal-repo");
        Path publicDir = tmp.resolve("public-repo");
        writeMeta(internalDir, "com.acme", "secret", "1.0");
        writeMeta(publicDir, "com.acme", "secret", "1.0", "99.0");
        writePom(internalDir, "com.acme", "secret", "1.0");
        writePom(publicDir, "com.acme", "secret", "99.0");

        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo internal = new MavenRepo("internal", internalDir.toUri(), new Http(), cas);
        MavenRepo pub = new MavenRepo("central", publicDir.toUri(), new Http(), cas);
        // Public first — would win without exclusive binding.
        RepoGroup group = new RepoGroup(List.of(pub, internal), List.of(List.of(), List.of("com.acme", "com.acme.*")));

        assertThat(group.availableVersions(Coordinate.of("com.acme", "secret", "0")))
                .containsExactly("1.0");

        Optional<RepoGroup.RepoFetched> hit = group.tryFetchPom(Coordinate.of("com.acme", "secret", "1.0"));
        assertThat(hit).isPresent();
        assertThat(hit.get().repo().name()).isEqualTo("internal");

        assertThat(group.tryFetchPom(Coordinate.of("com.acme", "secret", "99.0")))
                .isEmpty();
    }

    @Test
    void unbound_group_skips_exclusive_specialists(@TempDir Path tmp) throws Exception {
        // Exclusive-bound repos only serve their claimed groupsinternal claims
        // com.acme, so junit is not looked up there even though a phantom version exists.
        Path aDir = tmp.resolve("a");
        Path bDir = tmp.resolve("b");
        writeMeta(aDir, "junit", "junit", "4.12");
        writeMeta(bDir, "junit", "junit", "4.13.2");
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo a = new MavenRepo("internal", aDir.toUri(), new Http(), cas);
        MavenRepo b = new MavenRepo("central", bDir.toUri(), new Http(), cas);
        RepoGroup group = new RepoGroup(List.of(a, b), List.of(List.of("com.acme"), List.of()));

        assertThat(group.availableVersions(Coordinate.of("junit", "junit", "0")))
                .containsExactly("4.13.2");
        assertThat(group.eligibleRepos(Coordinate.of("junit", "junit", "0")))
                .extracting(MavenRepo::name)
                .containsExactly("central");
    }

    @Test
    void unclaimed_group_falls_back_to_specialists_on_a_full_miss(@TempDir Path tmp) throws Exception {
        // Google Maven hosts groups outside the built-in binding list (com.google.gms, ...).
        // The fast path skips specialists for unclaimed groups; a full miss must still rescue
        // through them instead of failing the resolve.
        Path centralDir = tmp.resolve("central");
        Path googleDir = tmp.resolve("google");
        Files.createDirectories(centralDir);
        writeMeta(googleDir, "com.google.gms", "google-services", "4.4.2");
        writePom(googleDir, "com.google.gms", "google-services", "4.4.2");
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo central = new MavenRepo("central", centralDir.toUri(), new Http(), cas);
        MavenRepo google = new MavenRepo("google", googleDir.toUri(), new Http(), cas);
        RepoGroup group = new RepoGroup(List.of(central, google), List.of(List.of(), List.of("androidx.*")));

        assertThat(group.availableVersions(Coordinate.of("com.google.gms", "google-services", "0")))
                .containsExactly("4.4.2");
        Optional<RepoGroup.RepoFetched> hit =
                group.tryFetchPom(Coordinate.of("com.google.gms", "google-services", "4.4.2"));
        assertThat(hit).isPresent();
        assertThat(hit.get().repo().name()).isEqualTo("google");
    }

    @Test
    void prepended_local_repos_preserve_exclusive_bindings(@TempDir Path tmp) throws Exception {
        // Path/git materialize used to rebuild RepoGroup without exclusive groups → jumpkick
        // was tried for every Central GAV. Prepend must keep exclusive specialists exclusive.
        Path pathDir = tmp.resolve("path-repo");
        Path centralDir = tmp.resolve("central-repo");
        writePom(pathDir, "com.local", "pathlib", "1.0");
        writePom(centralDir, "org.junit.jupiter", "junit-jupiter", "5.10.0");
        writeMeta(centralDir, "org.junit.jupiter", "junit-jupiter", "5.10.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo pathRepo = new MavenRepo("path", pathDir.toUri(), new Http(), cas);
        MavenRepo jumpkick = new MavenRepo("jumpkick", tmp.resolve("empty-jk").toUri(), new Http(), cas);
        MavenRepo central = new MavenRepo("central", centralDir.toUri(), new Http(), cas);
        RepoGroup base = new RepoGroup(
                List.of(jumpkick, central),
                List.of(List.of("cc.jumpkick", "cc.jumpkick.*", "build.jumpkick", "build.jumpkick.*"), List.of()));
        RepoGroup merged = base.withReposPrepended(List.of(pathRepo));

        assertThat(merged.eligibleRepos(Coordinate.of("org.junit.jupiter", "junit-jupiter", "0")))
                .extracting(MavenRepo::name)
                .containsExactly("path", "central");
        // the workspace-local path repo answers first even for claimed groups
        // a locally-built artifact outranks the exclusive remote binding.
        assertThat(merged.eligibleRepos(Coordinate.of("cc.jumpkick", "jk-test-runner", "0")))
                .extracting(MavenRepo::name)
                .containsExactly("path", "jumpkick");
        assertThat(merged.tryFetchPom(Coordinate.of("com.local", "pathlib", "1.0")))
                .isPresent()
                .get()
                .extracting(f -> f.repo().name())
                .isEqualTo("path");
    }

    @Test
    void prepended_path_repo_serves_a_claimed_group(@TempDir Path tmp) throws Exception {
        // user binds com.acme exclusively to their internal repo AND adds a path dep
        // in that namespace. The path repo must serve it — pre-fix the exclusive claim shadowed
        // the prepended repo and the remote copy silently won (or the fetch 404'd).
        Path internalDir = tmp.resolve("internal-repo");
        Path pathDir = tmp.resolve("path-repo");
        writePom(internalDir, "com.acme", "widget", "1.0"); // stale remote copy of the same GAV
        writePom(pathDir, "com.acme", "widget", "1.0"); // workspace build
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo internal = new MavenRepo("internal", internalDir.toUri(), new Http(), cas);
        MavenRepo pathRepo = new MavenRepo("path", pathDir.toUri(), new Http(), cas);
        RepoGroup merged = new RepoGroup(List.of(internal), List.of(List.of("com.acme", "com.acme.*")))
                .withReposPrepended(List.of(pathRepo));

        Optional<RepoGroup.RepoFetched> hit = merged.tryFetchPom(Coordinate.of("com.acme", "widget", "1.0"));
        assertThat(hit).isPresent();
        assertThat(hit.get().repo().name()).isEqualTo("path");
    }

    @Test
    void earlier_repo_beats_a_later_repos_warm_mirror(@TempDir Path tmp) throws Exception {
        // group-level local-first let any warm mirror shadow an earlier cold repo,
        // inverting repo-order precedence. Local-first is per-repo, in order.
        Path aDir = tmp.resolve("a-repo");
        Path bDir = tmp.resolve("b-repo");
        writePom(aDir, "com.example", "lib", "1.0");
        writePom(bDir, "com.example", "lib", "1.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo a = new MavenRepo("first", aDir.toUri(), new Http(), cas);
        MavenRepo b = new MavenRepo("second", bDir.toUri(), new Http(), cas);
        // Warm ONLY b's mirror (a previous resolve that ran before `first` was prepended).
        b.fetchPom(Coordinate.of("com.example", "lib", "1.0"));

        RepoGroup group = new RepoGroup(List.of(a, b));
        Optional<RepoGroup.RepoFetched> hit = group.tryFetchPom(Coordinate.of("com.example", "lib", "1.0"));
        assertThat(hit).isPresent();
        assertThat(hit.get().repo().name()).isEqualTo("first");
    }

    @Test
    void local_first_prefers_central_mirror_without_hitting_specialist(@TempDir Path tmp) throws Exception {
        Path centralDir = tmp.resolve("central-repo");
        writePom(centralDir, "com.example", "widget", "1.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        // Specialist first (would 404 on a real network); central has the local POM.
        MavenRepo jumpkick = new MavenRepo("jumpkick", tmp.resolve("empty").toUri(), new Http(), cas);
        MavenRepo central = new MavenRepo("central", centralDir.toUri(), new Http(), cas);
        RepoGroup group =
                new RepoGroup(List.of(jumpkick, central), List.of(List.of("cc.jumpkick", "cc.jumpkick.*"), List.of()));

        Optional<RepoGroup.RepoFetched> hit = group.tryFetchPom(Coordinate.of("com.example", "widget", "1.0"));
        assertThat(hit).isPresent();
        assertThat(hit.get().repo().name()).isEqualTo("central");
    }

    private static void writeMeta(Path repoRoot, String group, String artifact, String... versions) throws Exception {
        Coordinate c = Coordinate.of(group, artifact, "0");
        Path meta = repoRoot.resolve(MavenLayout.metadataPath(c));
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

    private static void writePom(Path repoRoot, String group, String artifact, String version) throws Exception {
        Coordinate c = Coordinate.of(group, artifact, version);
        Path pom = repoRoot.resolve(MavenLayout.pomPath(c));
        Files.createDirectories(pom.getParent());
        Files.writeString(
                pom,
                "<project><modelVersion>4.0.0</modelVersion><groupId>"
                        + group
                        + "</groupId><artifactId>"
                        + artifact
                        + "</artifactId><version>"
                        + version
                        + "</version></project>");
    }
}
