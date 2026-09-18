// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.resolver.StallWatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The shadow renders that failed, so every reader of the same POM within one stall window gets
 * the failure at once instead of waiting the window out again: one job reads a module's manifest
 * several times over — for its identity, its journal row, its own work and its settlement — and a
 * repository that stopped answering has not started again between two of them. A failure is keyed
 * by the module and its {@code pom.xml}'s size and modification time, so an edited POM renders
 * afresh, and it expires after two stall windows ({@value #MIN_TTL_MS} ms at least) — the one the
 * failed render spent and the one the job's own import may spend after it, whose settlement reads
 * the manifest once more — so a repository back on its feet is asked again.
 */
final class ShadowRenderFailures {

    /** The shortest time a failure is remembered, for a stall window set below it. */
    static final long MIN_TTL_MS = 10_000L;

    private record Failure(String stamp, long untilNanos, IOException cause) {}

    private final Clock clock;
    private final long ttlNanos;
    private final Map<Path, Failure> failures = new ConcurrentHashMap<>();

    ShadowRenderFailures(Clock clock, long ttlMillis) {
        this.clock = clock;
        this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
    }

    /** Failures remembered for two resolve stall windows ({@code JK_RESOLVE_TIMEOUT_MS}), the floor applied. */
    static ShadowRenderFailures forStallWindow() {
        return new ShadowRenderFailures(Clock.SYSTEM, Math.max(2 * StallWatch.envWindowMs(), MIN_TTL_MS));
    }

    /** Remember that rendering {@code module}'s shadow failed with {@code cause}, as its POM stands now. */
    void remember(Path module, IOException cause) {
        failures.put(module, new Failure(stamp(module), clock.nanos() + ttlNanos, cause));
    }

    /**
     * The failure the last render of {@code module} ended in, while its POM is unchanged and the
     * window has not passed; empty otherwise, and the stale entry is dropped.
     */
    Optional<IOException> recall(Path module) {
        Failure failure = failures.get(module);
        if (failure == null) return Optional.empty();
        if (clock.nanos() - failure.untilNanos() >= 0 || !failure.stamp().equals(stamp(module))) {
            failures.remove(module, failure);
            return Optional.empty();
        }
        return Optional.of(failure.cause());
    }

    /** Test seam: how many modules a failure is remembered for right now. */
    int size() {
        return failures.size();
    }

    /** The module's {@code pom.xml} as size and modification time; a POM that cannot be read stamps as absent. */
    private static String stamp(Path module) {
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(module.resolve(ManifestPaths.POM), BasicFileAttributes.class);
            return attrs.size() + "@" + attrs.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return "absent";
        }
    }
}
