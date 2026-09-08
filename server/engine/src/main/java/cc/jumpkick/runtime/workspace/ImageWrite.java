// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Errors;
import cc.jumpkick.http.Http;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.PluginBuild;
import cc.jumpkick.runtime.base.BaseImageDigest;
import cc.jumpkick.runtime.base.ImageCredentials;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The write-image verdict once {@link ImagePlans} has resolved the inputs: restore the tarball
 * from the packaging cache, fork the image worker — or, offline, refuse before the fork.
 */
final class ImageWrite {

    private ImageWrite() {}

    /** The image worker fork, indirect so a test can observe that no worker was forked. */
    interface WorkerFork {
        String run(String base);
    }

    /**
     * The write-image verdict once inputs are resolved: restore the tarball from the action cache,
     * or fork the worker — or, offline, refuse <em>before</em> the fork.
     *
     * <p>Offline handling is three-way. A digest-pinned base with a warm cache restores with no
     * network and no fork — the one offline image path that works. A tag base is never pinned
     * offline (the registry round trip is skipped rather than attempted and swallowed), and a cache
     * miss forks a worker whose first act is to refuse — after the entire upstream pipeline already
     * ran. So on a miss the refusal happens here, naming the base and the escape hatch the cache
     * comment below describes: a {@code base = "…@sha256:…"} pin needs no round trip.
     */
    static void restoreOrBuild(
            TaskContext ctx,
            JkBuild project,
            BuildLayout layout,
            ImageConfig config,
            Path cache,
            @Nullable Path tarballPath,
            List<Path> depJars,
            List<Path> snapshotJars,
            @Nullable Path classesDir,
            @Nullable String chosen,
            Path workerJar,
            WorkerFork fork)
            throws IOException {
        boolean offline = SessionContext.current().offline();
        // Packaging cache — tarball only. A registry push is a network
        // side-effect (the remote's state is unknown), so it's never skipped.
        // The tarball is a pure function of the main jar, the dependency jars,
        // the main class, the image config, and the image-builder plugin version.
        // The base image is an input, not a name. `eclipse-temurin:25-jre` moves, and a
        // key carrying the tag string is byte-identical across a republish — it would
        // serve a tarball built on layers the registry no longer has. Resolve once,
        // key on the digest, and hand the worker the pinned reference so the image
        // that ships is the image the key describes.
        String declared = Objects.requireNonNull(config.base(), "image base");
        Optional<String> pinnedBase = offline && !BaseImageDigest.pinned(declared)
                ? Optional.empty()
                : BaseImageDigest.pin(declared, new Http(), ImageCredentials.resolve(declared, layout.moduleRoot()));
        String base = pinnedBase.orElse(declared);

        ActionCache ac = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        // The pin probe carries the same registry credential the worker's pull leg gets, so a
        // private base pins and caches like a public one. A base that still cannot be resolved
        // (offline, an unreachable registry, a rejected credential) leaves nothing in the key that
        // identifies the layers underneath the tarball, so it is not cached at all rather than
        // cached wrongly. Writing `base = "…@sha256:…"` needs no registry round trip and always
        // caches.
        boolean useCache = tarballPath != null
                && pinnedBase.isPresent()
                && !SessionContext.current().config().rebuildOr(false);
        String imgTask = null, imgKey = null;
        if (tarballPath != null && useCache) {
            List<String> tokens = ImagePlans.imageTokens(
                    layout.mainJar(),
                    depJars,
                    snapshotJars,
                    classesDir,
                    chosen,
                    base,
                    config,
                    appTreeToken(project, layout),
                    workerJar);
            imgTask = ActionKey.qualifiedTaskId(TaskNames.WRITE_IMAGE, tarballPath);
            imgKey = ActionKey.forArtifact(imgTask, BuildIdentity.cacheKeyVersion(), tokens);
            var hit = ac.lookup(imgKey);
            Path tarballDir = Objects.requireNonNull(tarballPath.getParent(), "tarball dir");
            if (hit.isPresent() && ac.restoreArtifacts(hit.get(), tarballDir)) {
                ctx.put(ImagePlans.IMAGE_REF, "");
                ctx.label(tarballPath.getFileName() + " up-to-date");
                ctx.progress(1);
                return;
            }
        }
        if (offline) {
            // The worker (Jib revalidates its base whatever the local cache holds) would refuse
            // anyway — but only after the whole upstream pipeline was paid for. Refuse at the
            // engine, before the fork, with the pin that makes offline image builds cacheable.
            String refusal = Errors.offlineRefusal(base) + "; pin the base by digest in jk.toml — [image] base ="
                    + " \"…@sha256:…\" — to build offline from the packaging cache";
            ctx.error("image", refusal);
            throw new IllegalStateException(refusal);
        }
        boolean daemonMode = tarballPath == null
                && (config.registry() == null || config.registry().isBlank());
        if (tarballPath != null) {
            ctx.label("write OCI tarball " + tarballPath.getFileName());
        } else if (daemonMode) {
            ctx.label("load into local daemon ("
                    + (config.dockerExecutable() != null ? config.dockerExecutable() : "docker/podman") + ")");
        } else {
            ctx.label("push to "
                    + config.targetReference(
                            project.project().name(), project.project().version()));
        }
        try {
            ctx.put(ImagePlans.IMAGE_REF, fork.run(base));
        } catch (RuntimeException e) {
            ctx.error("image", Errors.text(e));
            throw e;
        }
        if (tarballPath != null && useCache) {
            ac.storeArtifacts(
                    imgTask,
                    imgKey,
                    Map.of(),
                    Objects.requireNonNull(tarballPath.getParent(), "tarball dir"),
                    List.of(tarballPath));
        }
        ctx.progress(1);
    }

    /**
     * Fingerprint of the packager app tree an app-tree image ships (empty when none is
     * declared). The tree's content is not derivable from the main jar + dep jars tokens — a
     * packager config flip (e.g. Quarkus fast-jar vs uber-jar) rewrites the tree without
     * touching either, and a stale cache hit would restore an image missing what the config now
     * demands.
     */
    private static String appTreeToken(JkBuild project, BuildLayout layout) throws IOException {
        var shape = PluginBuild.shape(project, layout.moduleRoot());
        String appDir = shape.map(sh -> sh.appDir()).orElse("");
        String appJar = shape.map(sh -> sh.appJar()).orElse("");
        if (appDir.isBlank() || appJar.isBlank()) return "";
        Path appRoot = layout.moduleTargetDir().resolve(appDir);
        return appDir + "|" + appJar + "|"
                + (Files.isDirectory(appRoot) ? ClasspathFingerprint.entry(appRoot) : "absent");
    }
}
