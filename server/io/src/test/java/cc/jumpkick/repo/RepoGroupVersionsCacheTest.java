// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoGroupVersionsCacheTest {

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
    }

    /**
     * The version list a remote answered is held for the process: the second ask is served without
     * a request even when the remote has since lost the catalog. Anchored on a remote-looking
     * transport because a {@code file://} directory's answers, like a loopback stub's, are never
     * memoized.
     */
    @Test
    void availableVersions_process_memo_hits_second_call(@TempDir Path tmp) throws Exception {
        RemoteStub remote = new RemoteStub("versions.example.test");
        new MavenStub(remote.served).metadata("com.example", "lib", "1.0", "2.0");
        RepoGroup group = new RepoGroup(List.of(remote.repo(tmp, "remote")));
        Coordinate coord = Coordinate.of("com.example", "lib", "0");

        List<String> first = group.availableVersions(coord);
        assertThat(first).containsExactlyInAnyOrder("1.0", "2.0");

        // The remote loses its catalog — the process memo still answers, without a request.
        remote.served.clear();
        int asked = remote.requestsFor(MavenStub.metadataPath("com.example", "lib"));
        assertThat(group.availableVersions(coord)).isEqualTo(first);
        assertThat(remote.requestsFor(MavenStub.metadataPath("com.example", "lib")))
                .isEqualTo(asked);

        RepoGroup.clearProcessVersionsCache();
        assertThat(group.availableVersions(coord)).isEmpty();
    }

    @Test
    void availableVersions_memo_is_scoped_to_repository_set(@TempDir Path tmp) throws Exception {
        Path repoA = tmp.resolve("a");
        Path repoB = tmp.resolve("b");
        writeMeta(repoA, "com.example", "lib", "1.0");
        writeMeta(repoB, "com.example", "lib", "2.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup groupA = new RepoGroup(List.of(new MavenRepo("a", repoA.toUri(), new Http(), cas)));
        RepoGroup groupB = new RepoGroup(List.of(new MavenRepo("b", repoB.toUri(), new Http(), cas)));

        assertThat(groupA.availableVersions(Coordinate.of("com.example", "lib", "0")))
                .containsExactly("1.0");
        // Different repo set must not reuse group A's answer.
        assertThat(groupB.availableVersions(Coordinate.of("com.example", "lib", "0")))
                .containsExactly("2.0");
    }

    @Test
    void pom_hit_memo_is_scoped_to_repository_set(@TempDir Path tmp) throws Exception {
        Path repoA = tmp.resolve("a");
        Path repoB = tmp.resolve("b");
        writePom(repoA, "com.example", "lib", "1.0", "from-a");
        writePom(repoB, "com.example", "lib", "1.0", "from-b");
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup groupA = new RepoGroup(List.of(new MavenRepo("a", repoA.toUri(), new Http(), cas)));
        RepoGroup groupB = new RepoGroup(List.of(new MavenRepo("b", repoB.toUri(), new Http(), cas)));
        Coordinate gav = Coordinate.of("com.example", "lib", "1.0");

        String a =
                Files.readString(groupA.tryFetchPom(gav).orElseThrow().fetched().cachePath());
        String b =
                Files.readString(groupB.tryFetchPom(gav).orElseThrow().fetched().cachePath());
        assertThat(a).contains("from-a");
        assertThat(b).contains("from-b");
    }

    @Test
    void memo_is_scoped_to_exclusive_bindings(@TempDir Path tmp) throws Exception {
        Path general = tmp.resolve("general");
        Path special = tmp.resolve("special");
        writeMeta(general, "com.example", "lib", "1.0");
        writeMeta(special, "com.example", "lib", "2.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        Http http = new Http();
        List<MavenRepo> repos = List.of(
                new MavenRepo("general", general.toUri(), http, cas),
                new MavenRepo("special", special.toUri(), http, cas));

        RepoGroup unbound = new RepoGroup(repos);
        RepoGroup bound = new RepoGroup(repos, List.of(List.of(), List.of("com.example")));
        assertThat(unbound.processIdentity()).isNotEqualTo(bound.processIdentity());

        // Unbound group answers first-hit-wins from the general repo and memoizes it.
        assertThat(unbound.availableVersions(Coordinate.of("com.example", "lib", "0")))
                .containsExactly("1.0");
        // The exclusive-bound group must not be served that memo: com.example is claimed by
        // the specialist, so only 2.0 is a legal answer.
        assertThat(bound.availableVersions(Coordinate.of("com.example", "lib", "0")))
                .containsExactly("2.0");
    }

    @Test
    void offline_and_online_sessions_never_share_a_memoized_answer(@TempDir Path tmp) throws Exception {
        Path repoDir = tmp.resolve("repo");
        writeMeta(repoDir, "com.example", "lib", "1.0", "2.0");
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup group = new RepoGroup(List.of(new MavenRepo("local", repoDir.toUri(), new Http(), cas)));
        Coordinate coord = Coordinate.of("com.example", "lib", "0");

        try {
            // Offline first: nothing mirrored into the named repo store yet, so [] is the honest
            // offline answer — and it gets memoized under the offline key.
            goOffline();
            assertThat(group.availableVersions(coord)).isEmpty();
        } finally {
            SessionContext.reset();
        }

        // An online session in the same process must not be served that memoized [].
        assertThat(group.availableVersions(coord)).containsExactlyInAnyOrder("1.0", "2.0");

        try {
            // Nor may the network-derived list leak back into a later offline session.
            goOffline();
            assertThat(group.availableVersions(coord)).isEmpty();
        } finally {
            SessionContext.reset();
        }
    }

    /**
     * The idle engine drops the positive resolve memos and says how many went: the effective POMs
     * built, the repository hits and the version lists — each count a memo's size at the time.
     * The memos are process-wide, and an earlier test's prefetch may still be writing hits and
     * POMs into them, so those two counts are read as at least what this test put there.
     */
    @Test
    void the_idle_drop_counts_each_memo_and_leaves_it_empty(@TempDir Path tmp) throws Exception {
        RemoteStub remote = new RemoteStub("memo.example.test");
        new MavenStub(remote.served)
                .metadata("com.example", "lib", "1.0")
                .pom("com.example", "lib", "1.0", MavenStub.emptyPom("com.example", "lib", "1.0"));
        MavenRepo repo = remote.repo(tmp, "remote");
        RepoGroup group = new RepoGroup(List.of(repo));
        Coordinate coord = Coordinate.of("com.example", "lib", "1.0");

        assertThat(group.availableVersions(Coordinate.of("com.example", "lib", "0")))
                .containsExactly("1.0");
        new EffectivePomBuilder(group).build(coord);

        assertThat(RepoGroup.dropVersionsMemo()).isEqualTo(1);
        assertThat(RepoGroup.dropHitMemos())
                .as("the POM the effective-POM build fetched")
                .isGreaterThanOrEqualTo(1);
        assertThat(EffectivePomBuilder.dropProcessMemo()).isGreaterThanOrEqualTo(1);
        assertThat(RepoGroup.dropVersionsMemo())
                .as("a drop leaves the memo empty")
                .isZero();
    }

    private static void goOffline() {
        SessionContext.installConfig(JkConfig.empty().withOffline(true));
    }

    private static void writeMeta(Path root, String group, String artifact, String... versions) throws Exception {
        Path dir = root.resolve(group.replace('.', '/')).resolve(artifact);
        Files.createDirectories(dir);
        StringBuilder vs = new StringBuilder();
        for (String v : versions) {
            vs.append("    <version>").append(v).append("</version>\n");
        }
        String body = """
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <versions>
                %s    </versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, vs);
        Files.writeString(dir.resolve("maven-metadata.xml"), body);
    }

    private static void writePom(Path root, String group, String artifact, String version, String name)
            throws Exception {
        Path dir = root.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        String body = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <name>%s</name>
                </project>
                """.formatted(group, artifact, version, name);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), body);
    }
}
