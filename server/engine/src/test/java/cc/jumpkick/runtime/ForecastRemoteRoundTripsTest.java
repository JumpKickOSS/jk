// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.testing.CommittedLocks;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.DirectoryCacheBackend;
import cc.jumpkick.task.RemoteCaches;
import cc.jumpkick.task.RemoteTier;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a machine with an empty cache pays to forecast a build from another machine's records: a
 * chain of three modules ({@code c} on {@code b} on {@code a}) built and uploaded by one checkout,
 * forecast by another checkout with its own cache and no blob of the first's. Every step forecasts
 * as the hit the build will take, without one blob fetched, because each record carries the
 * interface token its consumers key on; and the forecast costs one lookup round trip per link in
 * the chain of keys, not one per step.
 */
class ForecastRemoteRoundTripsTest {

    @Test
    void a_cold_checkout_forecasts_a_chain_from_records_alone(@TempDir Path tmp) throws Exception {
        Path remote = Files.createDirectories(tmp.resolve("remote"));
        Path a = chain(tmp.resolve("a"));
        Path lockA = Files.copy(a.resolve("jk-lock.toml"), tmp.resolve("lock-as-written.toml"));
        RemoteTier upload = new RemoteTier(new DirectoryCacheBackend(remote), true);
        Path cacheA = tmp.resolve("cache-a");
        // Each machine's memos live under its own cache, as an engine request binds them.
        SessionContext.where(
                SessionContext.current().withCacheDir(cacheA),
                () -> RemoteCaches.where(upload, () -> {
                    build(a, cacheA);
                    return null;
                }));
        assertThat(upload.drain(Duration.ofMinutes(1))).isTrue();

        Path b = copy(a, tmp.resolve("b"));
        assertThat(Files.readString(b.resolve("jk-lock.toml")))
                .as("the build left the lock as written")
                .isEqualTo(Files.readString(lockA));
        assertThat(LockFreshness.isStale(b, b.resolve("jk-lock.toml")))
                .as("the second checkout's lock is fresh")
                .isFalse();
        Path cacheB = tmp.resolve("cache-b");
        Counting counting = new Counting(new DirectoryCacheBackend(remote));
        RemoteTier read = new RemoteTier(counting);
        List<TaskForecast.Module> forecast = SessionContext.where(
                SessionContext.current().withCacheDir(cacheB),
                () -> RemoteCaches.where(read, () -> {
                    BuildGraph.Result graph = BuildGraph.resolve(b, JkBuildParser.parse(b.resolve("jk.toml")));
                    ActionCache cache = new ActionCache(JkStores.cacheCas(cacheB), CacheTree.ACTIONS.under(cacheB));
                    return ForecastWalk.of(graph, JkStores.cacheCas(cacheB), cache, cacheB, true);
                }));

        List<String> steps = new ArrayList<>();
        for (TaskForecast.Module m : forecast) {
            for (TaskForecast.Task t : m.steps())
                steps.add(m.dir().getFileName() + ":" + t.name() + "=" + t.status() + " (" + t.text() + ")");
        }
        assertThat(steps)
                .as("every step of the chain forecasts as a hit from the remote; lookups " + counting.lookups.get())
                .filteredOn(s -> s.contains(":compile-java=") || s.contains(":package-jar="))
                .hasSize(6)
                .allMatch(s -> s.contains("=CACHED"));
        assertThat(counting.blobsOpened.get()).as("no blob fetched to forecast").isZero();
        // One question per key, never two: the walk prices a module's steps in order and a
        // consumer after its producer, so this chain asks its six keys in six round trips. Without
        // the stated interface it asked four and priced b and c as rebuilds.
        assertThat(counting.lookups.get())
                .as("lookups for " + counting.keys.get() + " keys: " + steps)
                .isLessThanOrEqualTo(6);
    }

    /** A backend that counts what it is asked. */
    private static final class Counting implements CacheBackend {
        private final CacheBackend inner;
        final AtomicInteger lookups = new AtomicInteger();
        final AtomicInteger keys = new AtomicInteger();
        final AtomicInteger blobsOpened = new AtomicInteger();

        Counting(CacheBackend inner) {
            this.inner = inner;
        }

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public Map<String, String> records(Lookup lookup) throws IOException {
            lookups.incrementAndGet();
            keys.addAndGet(lookup.keys().size());
            return inner.records(lookup);
        }

        @Override
        public Set<String> missing(Map<String, Long> blobs) throws IOException {
            return inner.missing(blobs);
        }

        @Override
        public void fetch(Map<String, Long> blobs, BlobSink sink) throws IOException {
            blobsOpened.addAndGet(blobs.size());
            inner.fetch(blobs, sink);
        }

        @Override
        public void putBlobs(Map<String, Path> blobs) throws IOException {
            inner.putBlobs(blobs);
        }

        @Override
        public void putRecord(String key, String recordText) throws IOException {
            inner.putRecord(key, recordText);
        }
    }

    private static void build(Path ws, Path cache) {
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false),
                new WorkspaceBuildListener() {});
        assertThat(result.errors()).as("errors").isEmpty();
        assertThat(result.success()).as("success").isTrue();
    }

    private static Path chain(Path ws) throws IOException {
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["a", "b", "c"]
                """);
        String upstream = null;
        for (String m : List.of("a", "b", "c")) {
            Path dir = Files.createDirectories(ws.resolve(m + "/src/com/example/" + m));
            Files.writeString(
                    ws.resolve(m + "/jk.toml"),
                    """
                    group   = "com.example"
                    name    = "%s"
                    version = "1.0.0"
                    java    = 25
                    """.formatted(m)
                            + (upstream == null ? "" : "\n[dependencies]\n" + upstream + " = { workspace = true }\n"));
            String body = upstream == null
                    ? "return 1;"
                    : "return com.example." + upstream + "." + upstream.toUpperCase() + ".value() + 1;";
            Files.writeString(
                    dir.resolve(m.toUpperCase() + ".java"), """
                    package com.example.%s;

                    public final class %s {
                        private %s() {}

                        public static int value() {
                            %s
                        }
                    }
                    """.formatted(m, m.toUpperCase(), m.toUpperCase(), body));
            upstream = m;
        }
        // The lock a committed project carries: stamped with its manifests' digest, so no build
        // re-locks it, and naming the project, so both checkouts are one project and key alike.
        // Unstamped, the first build would resolve the default test platform to write one, which
        // needs those artifacts in the store or the network; without them it kept the stale lock,
        // and the second checkout's forecast priced every step as waiting on a lock update.
        CommittedLocks.write(ws);
        return ws;
    }

    private static Path copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path src : files.toList()) {
                Path rel = from.relativize(src);
                if (rel.toString().contains("target")) continue;
                Path dst = to.resolve(rel.toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else Files.copy(src, dst);
            }
        }
        return to;
    }
}
