// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A host's request permits are one pool however its name is spelled. */
class HostRateLimiterTest {

    @Test
    void two_spellings_of_one_host_share_its_permits() throws Exception {
        HostRateLimiter limiter = new HostRateLimiter(1);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                limiter.run("Repo.Example.ORG", () -> {
                    holding.countDown();
                    release.await();
                    return Boolean.TRUE;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holding.await();
        Thread other = Thread.ofVirtual().start(() -> {
            try {
                limiter.run("repo.example.org", () -> {
                    second.countDown();
                    return Boolean.TRUE;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(second.await(300, TimeUnit.MILLISECONDS))
                .as("the second spelling waits for the first's permit")
                .isFalse();
        release.countDown();
        assertThat(second.await(10, TimeUnit.SECONDS)).isTrue();
        first.join();
        other.join();
    }
}
