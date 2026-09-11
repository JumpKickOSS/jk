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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk image --offline} splits on whether the base is digest-pinned. Pinned + warm packaging
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
    void offline_pinned_base_warm_cache_restores_without_forking(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp, PINNED_BASE);
        seedPackagingCache(fx);
        Files.deleteIfExists(fx.tarball());

        AtomicInteger forks = new AtomicInteger();
        BuildPlanResult result = runOffline(fx, forks);

        assertThat(result.success()).isTrue();
        assertThat(forks.get())
                .as("a warm-cache offline build must not fork the image worker")
                .isZero();
        assertThat(fx.tarball()).exists();
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
                base, null, null, List.of(), Map.of(), Map.of(), null, null, List.of(), null, null, null, false);
        return new Fixture(project, layout, config, tmp.resolve("cache"), tarball, workerJar);
    }

    /** Store the tarball under the same key {@code restoreOrBuild} derives, exactly as production does. */
    private static void seedPackagingCache(Fixture fx) throws Exception {
        List<String> tokens = ImagePlans.imageTokens(
                fx.layout().mainJar(),
                new ImagePlans.RuntimeJars(List.of(), List.of(), List.of(), Map.of()),
                null,
                MAIN,
                fx.config().base(),
                fx.config(),
                "",
                fx.workerJar());
        String task = ActionKey.qualifiedTaskId("write-image", fx.tarball());
        String key = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokens);
        Files.writeString(fx.tarball(), "TARBALL");
        new ActionCache(JkStores.cacheCas(fx.cache()), CacheTree.ACTIONS.under(fx.cache()), JkStores.storeCas())
                .storeArtifacts(task, key, Map.of(), fx.tarball().getParent(), List.of(fx.tarball()));
    }

    /** One-step plan around {@code restoreOrBuild} under an {@code --offline} session. */
    private static BuildPlanResult runOffline(Fixture fx, AtomicInteger forks) throws Exception {
        Task step = Task.builder("write-image")
                .ticks(1)
                .execute(ctx -> ImageWrite.restoreOrBuild(
                        ctx,
                        fx.project(),
                        fx.layout(),
                        fx.config(),
                        fx.cache(),
                        fx.tarball(),
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
