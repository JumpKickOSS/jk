// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A repository that answers nothing within the timeout, ahead of one that serves the coordinate:
 * the first coordinate waits it out, every later one asks the serving repository first, and a
 * coordinate only the silent repository could serve still reaches it.
 */
class RepoGroupSilentTest {

    private static final URI SILENT = URI.create("https://gitlab.example/api/v4/projects/7/packages/maven/");

    /** Every request times out; the URIs asked are kept. */
    private static final class Silent implements RepoTransport {
        final List<URI> asked = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException {
            asked.add(uri);
            throw new IOException("GET " + uri + " got no answer", new HttpTimeoutException("request timed out"));
        }

        @Override
        public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
            return 0;
        }
    }

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
        RunNotices.clear();
    }

    @AfterEach
    void reset() {
        RunNotices.clear();
    }

    @Test
    void a_repository_that_times_out_is_asked_last_for_the_rest_of_the_run(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cas"));
        Silent silent = new Silent();
        MavenRepo gitlab = MavenRepo.overTransport(
                "gitlab", SILENT, silent, cas, RepoCredential.ANONYMOUS, null, false, false, false);
        Path central = tmp.resolve("central");
        for (String artifact : List.of("lib", "other")) {
            Path pom = central.resolve("com/example/" + artifact + "/1.0/" + artifact + "-1.0.pom");
            Files.createDirectories(pom.getParent());
            Files.writeString(
                    pom,
                    "<project><groupId>com.example</groupId><artifactId>" + artifact
                            + "</artifactId><version>1.0</version></project>");
            Files.writeString(
                    central.resolve("com/example/" + artifact + "/maven-metadata.xml"),
                    "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>");
        }
        RepoGroup group = new RepoGroup(List.of(gitlab, new MavenRepo("central", central.toUri(), new Http(), cas)));

        assertThat(group.tryFetchPom(Coordinate.of("com.example", "lib", "1.0")))
                .isPresent();
        assertThat(silent.asked).as("the first coordinate pays the timeout").isNotEmpty();
        silent.asked.clear();

        assertThat(group.availableVersions(Coordinate.of("com.example", "other", "0"), Set.of("1.0"), false))
                .contains("1.0");
        assertThat(group.tryFetchPom(Coordinate.of("com.example", "other", "1.0")))
                .isPresent();
        assertThat(silent.asked).as("the serving repository answered first").isEmpty();

        assertThatThrownBy(() -> group.tryFetchPom(Coordinate.of("com.acme", "private", "1.0")))
                .as("when nobody serves it, the silence is the answer")
                .hasMessageContaining("no answer");
        assertThat(silent.asked)
                .as("a coordinate nobody else serves still reaches it")
                .anyMatch(uri -> uri.getPath().contains("/com/acme/private/"));
    }
}
