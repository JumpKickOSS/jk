// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A POM head is scanned for Gradle's marker once per POM content: the answer is kept beside the
 * metadata cache, so a fresh engine reads it back instead of scanning, and a changed POM is
 * scanned again.
 */
class GradleMarkerAnswersTest {

    @Test
    void an_answer_outlives_the_engine_that_found_it(@TempDir Path tmp) throws Exception {
        MavenRepo repo = new MavenRepo("central", tmp.resolve("never-dialed").toUri(), new Http(), new Cas(tmp));
        String marked = "<?xml version=\"1.0\"?>\n<!-- " + GradleModuleMetadata.POM_MARKER + " -->\n<project/>\n";
        RepoGroup.RepoFetched kmp = served(repo, tmp.resolve("kmp.pom"), marked);
        RepoGroup.RepoFetched plain = served(repo, tmp.resolve("plain.pom"), "<project/>\n");

        long before = GradleMarkerAnswers.SCANS.sum();
        assertThat(GradleMarkerAnswers.marked(kmp)).isTrue();
        assertThat(GradleMarkerAnswers.marked(plain)).isFalse();
        assertThat(GradleMarkerAnswers.marked(kmp)).isTrue();
        assertThat(GradleMarkerAnswers.SCANS.sum() - before).isEqualTo(2);
        assertThat(repo.metadataDir().resolve(GradleMarkerAnswers.FILE)).exists();

        // A fresh engine: nothing in memory, and the POMs themselves gone from disk.
        GradleMarkerAnswers.drop();
        Files.delete(kmp.fetched().cachePath());
        Files.delete(plain.fetched().cachePath());
        assertThat(GradleMarkerAnswers.marked(kmp)).isTrue();
        assertThat(GradleMarkerAnswers.marked(plain)).isFalse();
        assertThat(GradleMarkerAnswers.SCANS.sum() - before)
                .as("read back, not scanned")
                .isEqualTo(2);

        // The same path with other bytes is another POM.
        RepoGroup.RepoFetched changed = served(repo, tmp.resolve("plain.pom"), marked + "<!-- republished -->\n");
        assertThat(GradleMarkerAnswers.marked(changed)).isTrue();
        assertThat(GradleMarkerAnswers.SCANS.sum() - before).isEqualTo(3);
    }

    @Test
    void a_torn_line_is_skipped_and_the_pom_scanned(@TempDir Path tmp) throws Exception {
        MavenRepo repo = new MavenRepo("central", tmp.resolve("never-dialed").toUri(), new Http(), new Cas(tmp));
        RepoGroup.RepoFetched plain = served(repo, tmp.resolve("plain.pom"), "<project/>\n");
        Files.createDirectories(repo.metadataDir());
        Files.writeString(
                repo.metadataDir().resolve(GradleMarkerAnswers.FILE),
                plain.fetched().sha256().substring(0, 40) + "\n");

        long before = GradleMarkerAnswers.SCANS.sum();
        assertThat(GradleMarkerAnswers.marked(plain)).isFalse();
        assertThat(GradleMarkerAnswers.SCANS.sum() - before).isEqualTo(1);
    }

    private static RepoGroup.RepoFetched served(MavenRepo repo, Path pom, String body) throws Exception {
        Files.writeString(pom, body, StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(pom);
        return new RepoGroup.RepoFetched(repo, new MavenRepo.Fetched(pom.toUri(), pom, sha, Files.size(pom)));
    }
}
