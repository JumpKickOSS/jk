// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.doctor.DoctorChecks;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsAgent;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.SourceLocation;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.winhost.HostRow;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock jk.toml moved past that cannot be updated is never carried silently (JK-K43HA). With an
 * empty store and the only repository a host that denies every request (403), the build stops on
 * the workspace's lock step naming the stale lock and the reason; the module path that freshens
 * on its own does the same; {@code jk explain}'s reason, {@code jk doctor}'s lock row and the agent
 * report name the same stale lock. On the base the module path warned on a free-form output line
 * and built on the old pins, and the doctor's row was OK. Offline, a stale lock whose pins the
 * local store still covers is kept with one warning and is not stamped fresh.
 */
@Tag("integration")
@SysProps.TempRoots({"jk.env.JK_STORE_DIR", "jk.m2.local"})
class StaleLockRefreshE2eTest {

    private @Nullable HttpServer denying;
    private final AtomicInteger denied = new AtomicInteger();

    @BeforeEach
    void denyEveryRequest() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            denied.incrementAndGet();
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        denying = server;
    }

    @AfterEach
    void stop() {
        if (denying != null) denying.stop(0);
    }

    @Test
    void a_stale_lock_that_cannot_be_updated_stops_the_build_and_every_surface_names_it(@TempDir Path tmp)
            throws Exception {
        InetSocketAddress at = Objects.requireNonNull(denying).getAddress();
        String repo = "http://" + at.getHostString() + ":" + at.getPort() + "/";
        Path ws = workspace(tmp, repo, "");
        Path rootLock = LockPaths.lockFile(ws);
        // The lock as the manifests stood, stamped with their digest: fresh, and nothing to fetch.
        LockfileWriter.write(new Lockfile(Lockfile.CURRENT_VERSION, "test", "jk-test", List.of()), rootLock);
        assertThat(LockFreshness.isStale(ws, rootLock)).isFalse();
        // The member grows a dependency the empty store lacks and the only host denies.
        Files.writeString(ws.resolve("app/jk.toml"), member("""

                [dependencies]
                absent = { group = "com.example.denied", version = "1.0.0" }
                """));
        assertThat(LockFreshness.isStale(ws, rootLock)).isTrue();
        String before = Files.readString(rootLock);

        // The build: the workspace's lock step fails, naming the stale lock and why.
        Path cache = tmp.resolve("cache");
        BuildAccumulator acc = new BuildAccumulator("build", ws.toString(), "com.example:ws", "cli");
        List<String> failed = new ArrayList<>();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 1, null, false, true),
                new WorkspaceBuildListener() {
                    @Override
                    public void onPreflightFailed(
                            String stage, long millis, String reason, @Nullable SourceLocation at) {
                        failed.add(stage);
                        acc.addPreflightFailure(stage, millis, reason, at);
                    }
                });
        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(Exit.RESOLVE);
        assertThat(failed).containsExactly("lock");
        assertThat(result.modules()).as("nothing built on the old pins").isEmpty();
        String error = String.join("\n", result.errors());
        assertThat(error)
                .as(error)
                .startsWith(LockFreshness.STALE + ", and updating it failed: ")
                .contains("com/example/denied/absent")
                .contains("run jk lock");
        assertThat(denied.get()).as("the resolver asked the denying host").isPositive();
        assertThat(Files.readString(rootLock)).as("the lock is left as it was").isEqualTo(before);

        // The agent report carries the same line.
        acc.stamp(new JobOutcome.Failed(result.exitCode()));
        BuildRecord record = acc.toRecord(1_000, true, 500, "test", null);
        assertThat(JkResultsAgent.render(record)).contains(LockFreshness.STALE);

        // A module built on its own freshens in its setup step: it refuses the same way, where it
        // returned nothing and the build went on with the old lock.
        assertThatThrownBy(() -> AutoLock.maybeReLock(
                        ws.resolve("app"),
                        LockfileReader.read(rootLock),
                        rootLock,
                        cache,
                        null,
                        List.of(),
                        true,
                        ResolveObserver.NOOP))
                .isInstanceOf(AutoLock.RefreshFailed.class)
                .hasMessageStartingWith(LockFreshness.STALE + ", and updating it failed: ")
                .hasMessageContaining("com/example/denied/absent");

        // jk explain: every step runs, for the stale lock.
        var graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        var app = graph.topoOrder().stream()
                .filter(u -> u.dir().endsWith("app"))
                .findFirst()
                .orElseThrow();
        ActionCache actions =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        assertThat(ForecastScope.alone(app, app.manifest(), cache, actions).unlocked())
                .startsWith(LockFreshness.STALE);

        // jk doctor: the lock row warns with the same phrase.
        HostRow row = DoctorChecks.lock(ws.resolve("app"));
        assertThat(row.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(row.detail()).startsWith(LockFreshness.STALE);
        assertThat(row.fix()).contains("jk lock");
    }

    @Test
    void offline_a_stale_lock_the_store_still_covers_is_kept_with_one_warning_and_not_stamped(@TempDir Path tmp)
            throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        // The engine adds the test-runner infra to every module's closure: the repository serves it.
        repoArtifact(repo, "org.junit.platform", "junit-platform-launcher", "1.10.0");
        repoArtifact(repo, "org.junit.jupiter", "junit-jupiter", "5.10.0");
        Path ws = workspace(tmp, repo.toUri().toString(), "");
        Path rootLock = LockPaths.lockFile(ws);
        Path cache = tmp.resolve("cache");
        // Locked online, from an empty dependency set: nothing to fetch, and the store covers it.
        assertThat(LockFlow.run(ws, cache, List.of(), false, null).status()).isZero();
        assertThat(LockFreshness.isStale(ws, rootLock)).isFalse();
        // An edit that moves the digest; offline, the lock cannot be re-derived.
        Files.writeString(ws.resolve("app/jk.toml"), member("\n# edited offline\n"));
        assertThat(LockFreshness.isStale(ws, rootLock)).isTrue();
        String before = Files.readString(rootLock);

        List<String> notices = new ArrayList<>();
        Session offline = Session.defaults().withConfig(JkConfig.empty().withOffline(true));
        Lockfile kept = SessionContext.where(offline, () -> {
            RunNotices.openSink(SessionContext.current().io(), (code, message) -> notices.add(message));
            try {
                return AutoLock.maybeReLock(
                        ws.resolve("app"),
                        LockfileReader.read(rootLock),
                        rootLock,
                        cache,
                        null,
                        List.of(),
                        true,
                        ResolveObserver.NOOP);
            } finally {
                RunNotices.closeSink(SessionContext.current().io());
            }
        });

        assertThat(kept).as("the build goes on with the kept lock").isNotNull();
        assertThat(notices).singleElement().satisfies(n -> assertThat(n)
                .startsWith(LockFreshness.STALE + ", and it was kept as it is: offline")
                .contains("jk lock"));
        assertThat(Files.readString(rootLock))
                .as("not re-stamped: the next online build updates it")
                .isEqualTo(before);
        assertThat(LockFreshness.isStale(ws, rootLock)).isTrue();
    }

    /** Minimal empty-zip bytes: a valid jar as far as fetching and hashing go. */
    private static final byte[] EMPTY_ZIP = {
        0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private static void repoArtifact(Path repo, String group, String artifact, String version) throws IOException {
        Path dir = repo.resolve(group.replace('.', '/') + "/" + artifact);
        Path vDir = Files.createDirectories(dir.resolve(version));
        Files.write(vDir.resolve(artifact + "-" + version + ".jar"), EMPTY_ZIP);
        Files.writeString(vDir.resolve(artifact + "-" + version + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>jar</packaging>
                </project>
                """.formatted(group, artifact, version));
        Files.writeString(dir.resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <release>%s</release>
                    <versions><version>%s</version></versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, version, version));
    }

    private static Path workspace(Path tmp, String repo, String extra) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["app"]

                [repositories]
                central = "%s"
                """.formatted(repo));
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), member(extra));
        Path src = Files.createDirectories(app.resolve("src/main/java/com/example"));
        Files.writeString(src.resolve("Main.java"), """
                package com.example;

                public final class Main {}
                """);
        return ws;
    }

    private static String member(String extra) {
        return """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                """ + extra;
    }
}
