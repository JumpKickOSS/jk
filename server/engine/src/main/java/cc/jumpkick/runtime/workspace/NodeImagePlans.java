// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ManifestImage;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Errors;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.ImageTable;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.NodeImageContent;
import cc.jumpkick.runtime.base.ImageCredentials;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk image} of a node module. A module with a {@code start} gets a Node.js server image on
 * a distroless base matching the locked major; a module whose output is static files gets an nginx
 * image only when {@code [image] kind = "static"}, and otherwise no image: its bundle already rides
 * in the image of the JVM module that serves it.
 */
final class NodeImagePlans {

    /** The static output's nginx image, chosen by {@code [image] kind}. */
    static final BuildPlanKey<Boolean> STATIC = BuildPlanKey.scalar("image-node-static", Boolean.class);

    private NodeImagePlans() {}

    /**
     * Whether {@code projectDir} is a dedicated node module. A manifest that does not parse is not
     * one here; the core plan reports why it does not parse.
     */
    static boolean isNodeModule(Path manifest, Path projectDir) {
        try {
            return NodeShape.kind(JkBuildParser.parse(manifest), projectDir) == NodeShape.Kind.MODULE;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Add the node module's image-plan and write-image steps after its node build. */
    static BuildPlan.Builder tail(
            BuildPlan.Builder builder,
            BuildPlanner.Inputs inputs,
            Path projectDir,
            Path cache,
            @Nullable String registry,
            @Nullable String tag,
            @Nullable String tarballArg,
            @Nullable String dockerExecutableArg) {
        return builder.stateKeys(ImagePlans.CONFIG, ImagePlans.TARBALL_PATH, ImagePlans.IMAGE_REF, STATIC)
                .addTask(planStep(projectDir, registry, tag, tarballArg, dockerExecutableArg))
                .addTask(writeStep(inputs, projectDir, cache));
    }

    private static Task planStep(
            Path projectDir,
            @Nullable String registry,
            @Nullable String tag,
            @Nullable String tarballArg,
            @Nullable String dockerExecutableArg) {
        return Task.builder(TaskNames.IMAGE_PLAN)
                .stage(BuildStage.IMAGE)
                .requires(TaskNames.NODE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve image config");
                    JkBuild project = ctx.require(BuildPlanner.PROJECT);
                    BuildLayout layout = ctx.require(BuildPlanner.LAYOUT);
                    ImageTable data = ManifestImage.merge(project.image(), GlobalConfig.image());
                    if (Boolean.TRUE.equals(data.aotCache())) {
                        String refusal = "[image] aot-cache trains a JVM; "
                                + project.project().name() + " is a node module — remove aot-cache";
                        ctx.error("image", refusal);
                        throw new IllegalStateException(refusal);
                    }
                    boolean staticSite = ImageTable.KIND_STATIC.equals(data.kind());
                    if (!staticSite && NodeImageContent.start(project, projectDir) == null) {
                        ctx.label("no image · " + project.project().name() + " builds static files the JVM module"
                                + " that depends on it serves; set [image] kind = \"static\" for an nginx image");
                        ctx.progress(1);
                        return;
                    }
                    Path tarballPath = ImagePlans.resolveTarballPath(tarballArg, layout);
                    if (tarballPath != null) ctx.put(ImagePlans.TARBALL_PATH, tarballPath);
                    ctx.put(STATIC, staticSite);
                    ctx.put(
                            ImagePlans.CONFIG,
                            config(data, project, projectDir, staticSite, registry, tag, dockerExecutableArg));
                    ctx.progress(1);
                })
                .build();
    }

    /**
     * The node module's image config: {@code [image]} over the user-global table, with a node
     * server's defaults — the distroless base of the locked major, port 3000, the non-root user
     * and {@code PORT} / {@code HOSTNAME} / {@code NODE_ENV} — or a static image's nginx base and
     * port 80, each under whatever the table sets.
     */
    static ImageConfig config(
            ImageTable data,
            JkBuild project,
            Path projectDir,
            boolean staticSite,
            @Nullable String registry,
            @Nullable String tag,
            @Nullable String dockerExecutableArg)
            throws IOException {
        String base = data.base();
        if (base == null || base.isBlank()) {
            if (staticSite) {
                base = NodeImageContent.STATIC_BASE;
            } else {
                Path lock = LockPaths.lockFile(projectDir);
                NodePin pin =
                        Files.isRegularFile(lock) ? LockfileReader.read(lock).node() : null;
                if (pin == null) throw new IOException("Node.js is not locked yet — run `jk lock`");
                base = NodeImageContent.defaultBase(pin);
            }
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (!staticSite) {
            env.put("PORT", "3000");
            env.put("HOSTNAME", "0.0.0.0");
            env.put("NODE_ENV", "production");
        }
        env.putAll(data.env());
        List<Integer> ports = !data.ports().isEmpty() ? data.ports() : List.of(staticSite ? 80 : 3000);
        String user = data.user() != null && !data.user().isBlank()
                ? data.user()
                : staticSite ? null : NodeImageContent.NONROOT;
        String dockerExe = dockerExecutableArg != null ? dockerExecutableArg : data.dockerExecutable();
        if (dockerExe == null || dockerExe.isBlank()) dockerExe = ImagePlans.detectDockerExecutable();
        return new ImageConfig(
                base,
                data.name(),
                user,
                ports,
                env,
                data.labels(),
                registry != null ? registry : data.registry(),
                tag != null ? tag : data.tag(),
                data.platforms(),
                null,
                dockerExe,
                null,
                false);
    }

    private static Task writeStep(BuildPlanner.Inputs inputs, Path projectDir, Path cache) {
        return Task.builder(TaskNames.WRITE_IMAGE)
                .stage(BuildStage.IMAGE)
                .kind(TaskKind.IO)
                .requires(TaskNames.IMAGE_PLAN)
                .ticks(1)
                .execute(ctx -> {
                    ImageConfig config = ctx.get(ImagePlans.CONFIG).orElse(null);
                    if (config == null) {
                        ctx.progress(1);
                        return;
                    }
                    JkBuild project = ctx.require(BuildPlanner.PROJECT);
                    BuildLayout layout = ctx.require(BuildPlanner.LAYOUT);
                    Path tarballPath = ctx.get(ImagePlans.TARBALL_PATH).orElse(null);
                    boolean staticSite = ctx.get(STATIC).orElse(false);
                    String declared = Objects.requireNonNull(config.base(), "image base");
                    NodeImageContent.Content content;
                    try {
                        content = staticSite
                                ? NodeImageContent.staticSite(project, projectDir)
                                : NodeImageContent.server(
                                        ctx,
                                        inputs,
                                        project,
                                        projectDir,
                                        ctx.require(BuildPlanner.NODE_HOME),
                                        declared);
                    } catch (IOException | RuntimeException e) {
                        ctx.error("image", Errors.text(e));
                        throw e;
                    }
                    ctx.label("layers · "
                            + String.join(
                                    ", ",
                                    content.layers().stream()
                                            .map(NodeImageContent.Layer::name)
                                            .toList()) + " on " + declared);
                    Path workerJar = PluginJar.IMAGE_BUILDER.locate(JkStores.storeCas());
                    ImageWrite.restoreOrBuild(
                            ctx,
                            project,
                            layout,
                            config,
                            cache,
                            tarballPath,
                            base -> List.of(
                                    "node:" + content.token(),
                                    "base:" + base,
                                    "cfg:" + ImagePlans.imageConfigToken(config),
                                    "worker:" + ClasspathFingerprint.entry(workerJar)),
                            base -> ImagePlans.forkWorker(
                                    workerJar, spec(cache, project, layout, config, base, content, tarballPath)));
                })
                .build();
    }

    /** The worker spec for a layered node image. */
    static SpecWriter spec(
            Path cache,
            JkBuild project,
            BuildLayout layout,
            ImageConfig config,
            String base,
            NodeImageContent.Content content,
            @Nullable Path tarballPath) {
        boolean daemonMode = tarballPath == null
                && (config.registry() == null || config.registry().isBlank());
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_IMAGE, null, "jk-image-builder")
                .configString("kind", "node")
                .configString("artifact", project.project().name())
                .configString("version", project.project().version())
                .configString("mode", tarballPath != null ? "tarball" : daemonMode ? "daemon" : "push")
                .configString("base", base)
                .configString("jkCache", cache.toAbsolutePath().toString());
        ImageCredentials.write(sw, base, config, project, tarballPath == null && !daemonMode, layout.moduleRoot());
        if (config.name() != null) sw.configString("name", config.name());
        if (config.user() != null) sw.configString("user", config.user());
        if (config.registry() != null) sw.configString("registry", config.registry());
        if (config.tag() != null) sw.configString("tag", config.tag());
        if (tarballPath != null)
            sw.configString("tarball", tarballPath.toAbsolutePath().toString());
        if (config.dockerExecutable() != null) sw.configString("dockerExecutable", config.dockerExecutable());
        if (!config.ports().isEmpty())
            sw.configList("ports", config.ports().stream().map(String::valueOf).toList());
        if (!config.env().isEmpty()) {
            sw.configList(
                    "env",
                    config.env().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList());
        }
        if (!config.labels().isEmpty()) {
            sw.configList(
                    "labels",
                    config.labels().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList());
        }
        if (!config.platforms().isEmpty()) sw.configList("platforms", config.platforms());
        List<String> layers = new ArrayList<>();
        for (NodeImageContent.Layer l : content.layers()) {
            layers.add(l.name() + "\t" + l.src().toAbsolutePath() + "\t" + l.dest());
        }
        sw.configList("layers", layers);
        if (!content.entrypoint().isEmpty()) sw.configList("entrypoint", content.entrypoint());
        if (content.workingDir() != null) sw.configString("workdir", content.workingDir());
        return sw;
    }
}
