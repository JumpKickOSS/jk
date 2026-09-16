// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.Await;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A fan-out fetch asks every candidate at once and answers with the first in repository order.
 * The legs that lost are stopped before the answer is returned, so a later repository's copy is
 * never placed into the store after the caller has moved on.
 */
class RepoGroupSettleTest {

    private static final Coordinate LIB = Coordinate.of("com.foo", "lib", "1.0");
    private static final String LIB_POM = MavenStub.path("com.foo", "lib", "1.0", ".pom");
    private static final String LIB_POM_IN_STORE = MavenLayout.pomPath(LIB);
    private static final String LIB_JAR = MavenStub.path("com.foo", "lib", "1.0", ".jar");

    @RegisterExtension
    final LoopbackHttp fast = new LoopbackHttp();

    @RegisterExtension
    final LoopbackHttp slow = new LoopbackHttp();

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    @Test
    void a_pom_leg_that_lost_to_an_earlier_answer_writes_nothing_after_the_fetch_returns(@TempDir Path tmp)
            throws Exception {
        // Bytes no Maven local repository holds, so neither leg is answered by adopting a copy.
        String pom = MavenStub.emptyPom("com.foo", "lib", "1.0")
                .replace("</project>", "<description>" + UUID.randomUUID() + "</description></project>");
        new MavenStub(fast).pomOnly("com.foo", "lib", "1.0", pom);
        new MavenStub(slow).pomOnly("com.foo", "lib", "1.0", pom);
        losingLegWritesNothing(tmp, LIB_POM, LIB_POM_IN_STORE, group -> group.tryFetchPom(LIB));
    }

    @Test
    void an_artifact_leg_that_lost_to_an_earlier_answer_writes_nothing_after_the_fetch_returns(@TempDir Path tmp)
            throws Exception {
        // The materialize phase fetches every lock row's artifact through the same walk. Bytes no
        // Maven local repository holds, so neither leg is answered by adopting a copy.
        byte[] jar = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        new MavenStub(fast).bytes(LIB_JAR, jar);
        new MavenStub(slow).bytes(LIB_JAR, jar);
        losingLegWritesNothing(
                tmp, LIB_JAR, MavenLayout.artifactPath(LIB), group -> group.tryFetchArtifact(LIB, () -> false));
    }

    private interface Fetch {
        Optional<RepoGroup.RepoFetched> from(RepoGroup group) throws Exception;
    }

    private void losingLegWritesNothing(Path tmp, String servedPath, String storePath, Fetch fetch) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        // The later repository holds its response open past the earlier one's answer.
        slow.beforeServe(path -> {
            if (!path.equals(servedPath)) return;
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Cas cas = new Cas(tmp.resolve("cas"));
        MavenRepo first = new MavenRepo("fast", fast.base(), new Http(), cas);
        MavenRepo second = new MavenRepo("slow", slow.base(), new Http(), cas);
        RepoGroup group = new RepoGroup(List.of(first, second));
        RepoArtifactStore slowStore = RepoArtifactStore.forRepository(cas.root(), "slow", second.baseUrl());

        Optional<RepoGroup.RepoFetched> hit = fetch.from(group);

        assertThat(hit).isPresent();
        assertThat(hit.get().repo().name()).isEqualTo("fast");
        assertThat(release.getCount())
                .as("the answer did not wait the slow leg out")
                .isEqualTo(1);
        assertThat(slowStore.locate(storePath))
                .as("the losing leg placed nothing")
                .isEmpty();
        // Let the slow repository answer into the void; the leg that asked is gone.
        release.countDown();
        Await.until(
                Duration.ofSeconds(5), () -> slow.requestsFor(servedPath) >= 1 || fast.requestsFor(servedPath) >= 1);
        Thread.sleep(300);
        assertThat(slowStore.locate(storePath))
                .as("no copy lands after the fetch returned")
                .isEmpty();
    }
}
