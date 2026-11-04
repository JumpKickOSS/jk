// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A caller that looked in the store before another caller's download of the same file stored it,
 * and reaches the flight only after that download's flight has ended, takes the stored file: the
 * file is requested once. The caller is held in that window on purpose; under load a descheduled
 * thread sits there by itself, which is how a lock came to ask for one POM twice.
 */
class FetchAfterFlightEndsTest {

    private static final Coordinate WIDGET = Coordinate.of("com.example", "widget", "1.0");

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private volatile @Nullable Thread held;
    private final CountDownLatch inWindow = new CountDownLatch(1);
    private final CountDownLatch leaderDone = new CountDownLatch(1);

    @BeforeEach
    void publish() {
        RepoGroup.clearProcessFetchCache();
        new MavenStub(http).leaf("com.example", "widget", "1.0");
        MavenRepo.storeMissed = () -> {
            if (Thread.currentThread() != held) return;
            inWindow.countDown();
            try {
                leaderDone.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @AfterEach
    void release() {
        MavenRepo.storeMissed = () -> {};
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void a_pom_stored_while_a_caller_waited_to_fetch_it_is_requested_once(@TempDir Path tmp) throws Exception {
        MavenRepo repo = repo(tmp);

        raceOnce(() -> repo.fetchPom(WIDGET));

        assertThat(http.requestsFor(MavenStub.path("com.example", "widget", "1.0", ".pom")))
                .isEqualTo(1);
        assertThat(http.requested()).as("each file is asked for once").doesNotHaveDuplicates();
    }

    @Test
    void a_jar_stored_while_a_caller_waited_to_fetch_it_is_requested_once(@TempDir Path tmp) throws Exception {
        MavenRepo repo = repo(tmp);

        raceOnce(() -> repo.fetchArtifact(WIDGET));

        assertThat(http.requestsFor(MavenStub.path("com.example", "widget", "1.0", ".jar")))
                .isEqualTo(1);
        assertThat(http.requested()).as("each file is asked for once").doesNotHaveDuplicates();
    }

    @FunctionalInterface
    private interface Fetch {
        MavenRepo.Fetched run() throws Exception;
    }

    /**
     * One caller misses the store and is held before the flight; a second downloads the file to
     * completion; then the first goes on. Both get the same stored file.
     */
    private void raceOnce(Fetch fetch) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MavenRepo.Fetched> late = pool.submit(() -> {
                held = Thread.currentThread();
                return fetch.run();
            });
            assertThat(inWindow.await(10, TimeUnit.SECONDS))
                    .as("the late caller reached the window")
                    .isTrue();
            MavenRepo.Fetched first = fetch.run();
            leaderDone.countDown();
            MavenRepo.Fetched second = late.get(10, TimeUnit.SECONDS);
            assertThat(second.cachePath()).isEqualTo(first.cachePath());
            assertThat(second.sha256()).isEqualTo(first.sha256());
        } finally {
            leaderDone.countDown();
            pool.shutdownNow();
        }
    }

    private MavenRepo repo(Path tmp) {
        return new MavenRepo(
                "test", http.base(), new Http(), new Cas(tmp.resolve("cas")), RepoCredential.ANONYMOUS, false);
    }
}
