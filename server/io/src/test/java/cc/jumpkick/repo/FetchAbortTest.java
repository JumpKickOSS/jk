// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The abort signal stops artifact fetches at the leg boundary between the local probe
 * and the network leg — an aborted fetch never touches the network, but a warm local hit still
 * completes (legs finish cleanly; abort only prevents starting the next one).
 */
class FetchAbortTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    void dropProcessFetchCache() {
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void aborted_fetch_never_starts_the_network_leg(@TempDir Path tempDir) {
        String jarPath = "/com/example/widget/1.0/widget-1.0.jar";
        http.served().put(jarPath, "jar-bytes".getBytes(StandardCharsets.UTF_8));
        RepoGroup repos = RepoGroup.of(
                new MavenRepo("test", http.base(), new Http(), new Cas(tempDir), RepoCredential.ANONYMOUS, false));

        assertThatThrownBy(() -> repos.tryFetchArtifact(Coordinate.of("com.example", "widget", "1.0"), () -> true))
                .isInstanceOf(MavenRepo.FetchAbortedException.class);
        assertThat(http.requested()).as("no network request once aborted").isEmpty();
    }

    @Test
    void warm_local_hit_still_completes_under_abort(@TempDir Path tempDir) throws Exception {
        String jarPath = "/com/example/widget/1.0/widget-1.0.jar";
        http.served().put(jarPath, "jar-bytes".getBytes(StandardCharsets.UTF_8));
        Cas cas = new Cas(tempDir);
        Coordinate coord = Coordinate.of("com.example", "widget", "1.0");

        // Warm the named repo store online with no abort.
        RepoGroup warmed =
                RepoGroup.of(new MavenRepo("test", http.base(), new Http(), cas, RepoCredential.ANONYMOUS, false));
        assertThat(warmed.tryFetchArtifact(coord)).isPresent();

        // Same coordinate with abort raised: the local-probe leg answers without network.
        RepoGroup.clearProcessFetchCache();
        http.clearRequests();
        RepoGroup again =
                RepoGroup.of(new MavenRepo("test", http.base(), new Http(), cas, RepoCredential.ANONYMOUS, false));
        assertThat(again.tryFetchArtifact(coord, () -> true)).isPresent();
        assertThat(http.requested()).as("warm hit needs no network").isEmpty();
    }

    @Test
    void unaborted_fetch_is_unaffected(@TempDir Path tempDir) throws Exception {
        String jarPath = "/com/example/widget/1.0/widget-1.0.jar";
        http.served().put(jarPath, "jar-bytes".getBytes(StandardCharsets.UTF_8));
        RepoGroup repos = RepoGroup.of(
                new MavenRepo("test", http.base(), new Http(), new Cas(tempDir), RepoCredential.ANONYMOUS, false));

        var hit = repos.tryFetchArtifact(Coordinate.of("com.example", "widget", "1.0"), () -> false);
        assertThat(hit).isPresent();
        assertThat(http.requested()).contains(jarPath);
    }

    @Test
    void abort_wins_over_not_found_reporting(@TempDir Path tempDir) {
        // Nothing http.served(): without abort this would be an empty Optional (full miss); with abort
        // the fetch stops at the boundary instead of concluding "not found" from a skipped leg.
        RepoGroup repos = RepoGroup.of(
                new MavenRepo("test", http.base(), new Http(), new Cas(tempDir), RepoCredential.ANONYMOUS, false));
        assertThatThrownBy(() -> repos.tryFetchArtifact(Coordinate.of("com.example", "absent", "1.0"), () -> true))
                .isInstanceOf(MavenRepo.FetchAbortedException.class);
        assertThat(http.requested()).isEmpty();
    }
}
