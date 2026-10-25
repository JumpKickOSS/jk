// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Asks a repository whether a version is already published there. The question is advisory: a
 * repository that does not answer in time, refuses the read or cannot be reached yields
 * {@link Verdict#UNKNOWN}, never a failure, so an offline machine still releases.
 */
public final class RemoteClaim {

    public enum Verdict {
        /** The repository holds the version's POM. */
        CLAIMED,
        /** The repository answered and does not hold it. */
        FREE,
        /** No answer: offline, refused, timed out. */
        UNKNOWN
    }

    private RemoteClaim() {}

    /** Start asking now; read the verdict with {@link CompletableFuture#join} when it is needed. */
    public static CompletableFuture<Verdict> start(
            RepoTransport transport,
            URI repository,
            RepoCredential credential,
            String group,
            String artifact,
            String version,
            Duration timeout) {
        String base = repository.toString().endsWith("/") ? repository.toString() : repository + "/";
        URI pom = URI.create(base + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
                + version + ".pom.sha1");
        return CompletableFuture.supplyAsync(() -> {
                    try {
                        return transport.fetch(pom, credential).isPresent() ? Verdict.CLAIMED : Verdict.FREE;
                    } catch (IOException unreachable) {
                        return Verdict.UNKNOWN;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return Verdict.UNKNOWN;
                    }
                })
                .completeOnTimeout(Verdict.UNKNOWN, timeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(failed -> Verdict.UNKNOWN);
    }
}
