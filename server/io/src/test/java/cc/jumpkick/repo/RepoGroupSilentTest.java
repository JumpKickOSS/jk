// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.HostRateLimiter;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A repository that answers nothing within the timeout, ahead of one that serves the coordinate:
 * the first coordinate waits it out, and the repository is asked nothing more for the rest of the
 * run, though it is no optional one and no loopback one.
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

    private final List<String> notices = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessVersionsCache();
        RepoGroup.clearProcessFetchCache();
        RunNotices.clear();
        RunNotices.openSink(SessionContext.current().io(), (code, message) -> notices.add(message));
    }

    @AfterEach
    void reset() {
        RunNotices.clear();
    }

    @Test
    void a_repository_that_times_out_is_passed_over_for_the_rest_of_the_run(@TempDir Path tmp) throws Exception {
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
        assertThat(group.tryFetchPom(Coordinate.of("com.acme", "private", "1.0")))
                .as("a coordinate nobody else serves is a miss")
                .isEmpty();
        assertThat(silent.asked).as("asked nothing more").isEmpty();
        assertThat(group.passedOver()).containsExactly(gitlab);
        assertThat(notices)
                .as("said once")
                .singleElement(InstanceOfAssertFactories.STRING)
                .contains("gitlab")
                .contains("skipped for the rest of this lock");
    }

    /**
     * Every permit of the host is held by a request the repository will not answer, and one more
     * request queues for a permit: once the held ones time out, the queued one is refused without
     * being sent, rather than wait out the timeout again.
     */
    @Test
    @Timeout(30)
    void a_request_queued_for_a_permit_is_refused_once_the_repository_went_silent(@TempDir Path tmp) throws Exception {
        int permits = HostRateLimiter.shared().permitsFor("queued.example");
        CountDownLatch holding = new CountDownLatch(permits);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        RepoTransport held = new RepoTransport() {
            @Override
            public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException, InterruptedException {
                if (!uri.getPath().endsWith(".pom")) return Optional.empty();
                sent.incrementAndGet();
                holding.countDown();
                release.await();
                throw new IOException("GET " + uri + " got no answer", new HttpTimeoutException("request timed out"));
            }

            @Override
            public int put(URI uri, byte[] body, String contentType, RepoCredential credential) {
                return 0;
            }
        };
        MavenRepo repo = MavenRepo.overTransport(
                "queued",
                URI.create("https://queued.example/maven/"),
                held,
                new Cas(tmp.resolve("cas")),
                RepoCredential.ANONYMOUS,
                null,
                false,
                false,
                false);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i <= permits; i++) {
            Coordinate coord = Coordinate.of("com.example", "lib" + i, "1.0");
            Thread t = Thread.ofPlatform().unstarted(() -> {
                try {
                    repo.fetchPom(coord);
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            threads.add(t);
            if (i < permits) t.start();
        }
        holding.await();
        Thread queued = threads.getLast();
        queued.start();
        // Every permit is held, so the first wait the last request reaches is the one for a permit.
        while (queued.getState() != Thread.State.WAITING) Thread.onSpinWait();
        release.countDown();
        for (Thread t : threads) t.join();

        assertThat(sent.get())
                .as("only the requests that held a permit were sent")
                .isEqualTo(permits);
        assertThat(failures).hasSize(permits + 1);
        assertThat(repo.passedOver()).isTrue();
    }
}
