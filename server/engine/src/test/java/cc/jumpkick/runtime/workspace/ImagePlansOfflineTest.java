// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk image -o} splits on whether the base is digest-pinned. Pinned + warm packaging
 * cache is the one offline image path that works: the tarball restores and no worker forks. A tag
 * base (or a cache miss) refuses in the engine, before the fork — the worker's own refusal
 * ({@code OciImageBuilder}) produces a similar failure only after the whole upstream pipeline ran,
 * which is why both cases here assert on the fork seam and not on output absence: the worker
 * refusing would fail these tests, the engine refusing passes them.
 */
class ImagePlansOfflineTest {

    private static final String PINNED_BASE = "eclipse-temurin:25-jre@sha256:" + "a".repeat(64);
    private static final String TAG_BASE = "eclipse-temurin:25-jre";
    private static final String MAIN = "com.example.Main";

    @Test
    void offline_pinned_base_with_the_tarball_in_place_does_not_fork(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp, PINNED_BASE);
        seedPackagingCache(fx);

        AtomicInteger forks = new AtomicInteger();
        BuildPlanResult result = runOffline(fx, forks);

        assertThat(result.success()).isTrue();
        assertThat(forks.get())
                .as("a tarball that is still the one jk wrote needs no worker")
                .isZero();
    }

    /** The cache keeps a record, not a second copy: a tarball that is gone cannot be restored from it. */
    @Test
    void offline_a_tarball_that_is_gone_or_changed_is_not_restored_from_the_cache(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp, PINNED_BASE);
        seedPackagingCache(fx);
        Files.writeString(fx.tarball(), "CHANGED BY SOMEONE ELSE");

        AtomicInteger forks = new AtomicInteger();
        BuildPlanResult result = runOffline(fx, forks);

        assertThat(result.success()).isFalse();
        assertThat(forks.get()).isZero();

        Files.delete(fx.tarball());
        assertThat(runOffline(fx, forks).success()).isFalse();
    }

    /** A daemon image jk loaded and the daemon still holds under the same ID skips the build. */
    @Test
    void offline_a_daemon_image_that_is_still_loaded_does_not_fork(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Path daemon = tmp.resolve("fake-docker");
        Files.writeString(daemon, "#!/bin/sh\necho sha256:abc123\n");
        Files.setPosixFilePermissions(daemon, PosixFilePermissions.fromString("rwxr-xr-x"));
        Fixture fx = fixture(tmp, PINNED_BASE, daemon.toString());
        String ref = fx.config().targetReference("app", "0.1.0");
        seedDaemonRecord(fx, ImageSidecar.ofDaemonImage(ref, "abc123"));

        AtomicInteger forks = new AtomicInteger();
        BuildPlanResult result = runOffline(fx, null, forks);

        assertThat(result.success()).isTrue();
        assertThat(forks.get()).isZero();

        seedDaemonRecord(fx, ImageSidecar.ofDaemonImage(ref, "someone-rebuilt-it"));
        assertThat(runOffline(fx, null, forks).success())
                .as("an image the daemon holds under another ID is not the one jk made")
                .isFalse();
    }

    @Test
    void offline_tag_base_refuses_before_the_worker_forks(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp, TAG_BASE);

        AtomicInteger forks = new AtomicInteger();
        BuildPlanResult result = runOffline(fx, forks);

        assertThat(result.success()).isFalse();
        assertThat(forks.get())
                .as("the refusal must come from the engine, before any worker fork")
                .isZero();
        String message = result.errors().toString();
        assertThat(message).contains(TAG_BASE);
        assertThat(message).contains("@sha256:");
    }

    /** Cold cache with a pinned base refuses too — pre-fork, not via a doomed worker. */
    @Test
    void offline_pinned_base_cold_cache_refuses_before_the_worker_forks(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp, PINNED_BASE);

        AtomicInteger forks = new AtomicInteger();
        BuildPlanResult result = runOffline(fx, forks);

        assertThat(result.success()).isFalse();
        assertThat(forks.get()).isZero();
    }

    private record Fixture(
            JkBuild project, BuildLayout layout, ImageConfig config, Path cache, Path tarball, Path workerJar) {}

    private static Fixture fixture(Path tmp, String base) throws Exception {
        return fixture(tmp, base, null);
    }

    private static Fixture fixture(Path tmp, String base, @Nullable String dockerExecutable) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
            group = "t"
            name = "app"
            version = "0.1.0"
            jdk = 25
            """);
        JkBuild project = JkBuildParser.parse(tmp.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(tmp, project);
        Files.createDirectories(layout.mainJar().getParent());
        Files.writeString(layout.mainJar(), "MAIN");
        Path tarball = layout.ociImageTar();
        Files.createDirectories(tarball.getParent());
        Path workerJar = Files.writeString(tmp.resolve("jk-image-builder.jar"), "WORKER");
        ImageConfig config = new ImageConfig(
                base,
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                List.of(),
                null,
                dockerExecutable,
                null,
                false);
        return new Fixture(project, layout, config, tmp.resolve("cache"), tarball, workerJar);
    }

    /** Write the tarball and record it under the same key {@code restoreOrBuild} derives, exactly as production does. */
    private static void seedPackagingCache(Fixture fx) throws Exception {
        Files.writeString(fx.tarball(), "TARBALL");
        String task = ActionKey.qualifiedTaskId("write-image", fx.tarball());
        record(fx, task, ImageSidecar.ofTarball(fx.tarball()));
    }

    private static void seedDaemonRecord(Fixture fx, Map<String, String> sidecar) throws Exception {
        record(fx, ActionKey.qualifiedTaskId("write-image", fx.layout().ociImageTar()), sidecar);
    }

    private static void record(Fixture fx, String task, Map<String, String> sidecar) throws Exception {
        List<String> tokens = ImagePlans.imageTokens(
                fx.layout().mainJar(),
                new ImagePlans.RuntimeJars(List.of(), List.of(), List.of(), Map.of()),
                null,
                MAIN,
                fx.config().base(),
                fx.config(),
                "",
                fx.workerJar());
        String key = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokens);
        new ActionCache(JkStores.cacheCas(fx.cache()), CacheTree.ACTIONS.under(fx.cache()), JkStores.storeCas())
                .storeWithOutputs(task, key, sidecar, Map.of());
    }

    /** One-step plan around {@code restoreOrBuild} under an {@code --offline} session. */
    private static BuildPlanResult runOffline(Fixture fx, AtomicInteger forks) throws Exception {
        return runOffline(fx, fx.tarball(), forks);
    }

    private static BuildPlanResult runOffline(Fixture fx, @Nullable Path tarball, AtomicInteger forks)
            throws Exception {
        Task step = Task.builder("write-image")
                .ticks(1)
                .execute(ctx -> ImageWrite.restoreOrBuild(
                        ctx,
                        fx.project(),
                        fx.layout(),
                        fx.config(),
                        fx.cache(),
                        tarball,
                        new ImagePlans.RuntimeJars(List.of(), List.of(), List.of(), Map.of()),
                        null,
                        MAIN,
                        fx.workerJar(),
                        base -> {
                            forks.incrementAndGet();
                            return "";
                        }))
                .build();
        BuildPlan plan = BuildPlan.builder("image")
                .stateKeys(ImagePlans.IMAGE_REF)
                .addTask(step)
                .build();
        Session offline = Session.defaults().withConfig(JkConfig.empty().withOffline(true));
        return SessionContext.where(offline, plan::run);
    }
}
