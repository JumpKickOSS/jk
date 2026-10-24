// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
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
 * The write-image verdict once {@link ImagePlans} has resolved the inputs: skip the build when the
 * image is still where jk put it ({@link ImageSidecar}), else fork the image worker — or, offline,
 * refuse before the fork.
 */
final class ImageWrite {

    private ImageWrite() {}

    /** The image worker fork, indirect so a test can observe that no worker was forked. */
    interface WorkerFork {
        String run(String base);
    }

    /**
     * The write-image verdict once inputs are resolved: skip the build when the image is still where
     * jk put it (the tarball in {@code target/}, or the daemon's store), or fork the worker — or,
     * offline, refuse <em>before</em> the fork.
     *
     * <p>Offline handling is three-way. A digest-pinned base whose image is still in place needs no
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
            ImagePlans.RuntimeJars jars,
            @Nullable Path classesDir,
            @Nullable String chosen,
            Path workerJar,
            WorkerFork fork)
            throws IOException {
        restoreOrBuild(
                ctx,
                project,
                layout,
                config,
                cache,
                tarballPath,
                base -> ImagePlans.imageTokens(
                        layout.mainJar(),
                        jars,
                        classesDir,
                        chosen,
                        base,
                        config,
                        appTreeToken(project, layout),
                        workerJar),
                fork);
    }

    /** What the image tarball is a function of, given the resolved {@code base}. */
    interface Tokens {
        List<String> of(String base) throws IOException;
    }

    /** As above, with the action-key tokens from {@code tokens}. */
    static void restoreOrBuild(
            TaskContext ctx,
            JkBuild project,
            BuildLayout layout,
            ImageConfig config,
            Path cache,
            @Nullable Path tarballPath,
            Tokens tokens,
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
        boolean daemonMode = tarballPath == null
                && (config.registry() == null || config.registry().isBlank());
        // Where a daemon image is loaded: the runtime the project names, else whichever answers.
        String daemonExe = !daemonMode
                ? null
                : config.dockerExecutable() != null ? config.dockerExecutable() : ImagePlans.detectDockerExecutable();
        // The image lives in one place — the tarball beside the build, or the daemon's store — and the
        // cache holds a small record that proves it is still the image this key describes (see
        // ImageSidecar), never a second copy of it. The pin probe carries the same registry credential
        // the worker's pull leg gets, so a private base pins and records like a public one. A base that
        // still cannot be resolved (offline, an unreachable registry, a rejected credential) leaves
        // nothing in the key that identifies the layers underneath the image, so it is not recorded at
        // all rather than recorded wrongly. Writing `base = "…@sha256:…"` needs no registry round trip
        // and always records. A push is a network side-effect (the remote's state is unknown): never skipped.
        boolean useCache = (tarballPath != null || daemonExe != null)
                && pinnedBase.isPresent()
                && !SessionContext.current().config().rebuildOr(false);
        ImageKey recorded = null;
        String daemonRef = config.targetReference(
                project.project().name(), project.project().version());
        if (useCache) {
            String imgTask = ActionKey.qualifiedTaskId(
                    TaskNames.WRITE_IMAGE, tarballPath != null ? tarballPath : layout.ociImageTar());
            String imgKey = ActionKey.forArtifact(imgTask, BuildIdentity.cacheKeyVersion(), tokens.of(base));
            recorded = new ImageKey(imgTask, imgKey);
            var hit = ac.lookup(imgKey);
            if (hit.isPresent()
                    && tarballPath != null
                    && ImageSidecar.tarballIntact(hit.get().inputs(), tarballPath)) {
                ctx.put(ImagePlans.IMAGE_REF, "");
                ctx.label(tarballPath.getFileName() + " up-to-date");
                ctx.progress(1);
                return;
            }
            if (hit.isPresent()
                    && daemonExe != null
                    && ImageSidecar.daemonHolds(hit.get().inputs(), daemonExe)) {
                ctx.put(
                        ImagePlans.IMAGE_REF,
                        Objects.requireNonNull(hit.get().inputs().get(ImageSidecar.IMAGE_REF)));
                ctx.label(daemonRef + " up-to-date in " + daemonExe);
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
        String built;
        try {
            built = fork.run(base);
            ctx.put(ImagePlans.IMAGE_REF, built);
        } catch (RuntimeException e) {
            ctx.error("image", Errors.text(e));
            throw e;
        }
        if (recorded != null && tarballPath != null && Files.isRegularFile(tarballPath)) {
            ac.storeWithOutputs(recorded.task(), recorded.key(), ImageSidecar.ofTarball(tarballPath), Map.of());
        } else if (recorded != null && daemonExe != null) {
            String loaded = built.isBlank() ? daemonRef : built;
            Optional<String> id = ImageSidecar.daemonImageId(daemonExe, loaded);
            if (id.isPresent()) record(ac, recorded, ImageSidecar.ofDaemonImage(loaded, id.get()));
        }
        ctx.progress(1);
    }

    /** The task pointer and the action key an image's record is stored under. */
    private record ImageKey(String task, String key) {}

    private static void record(ActionCache ac, ImageKey image, Map<String, String> sidecar) {
        try {
            ac.storeWithOutputs(image.task(), image.key(), sidecar, Map.of());
        } catch (IOException e) {
            // an image that cannot be recorded is built again next time, which is all the record saves
            Log.debug("image: the record is not kept", e);
        }
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
