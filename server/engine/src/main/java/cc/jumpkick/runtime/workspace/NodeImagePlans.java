// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ManifestImage;
import cc.jumpkick.config.UserConfigParseException;
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
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.NodeImageContent;
import cc.jumpkick.runtime.base.ImageCredentials;
import cc.jumpkick.task.WorkerClosure;
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
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
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
            @Nullable String tag,
            @Nullable String tarballArg,
            @Nullable String dockerExecutableArg) {
        return builder.stateKeys(ImagePlans.CONFIG, ImagePlans.TARBALL_PATH, ImagePlans.IMAGE_REF, STATIC)
                .extend(planStep(inputs, projectDir, cache, tag, tarballArg, dockerExecutableArg, false))
                .extend(writeStep(inputs, projectDir, cache));
    }

    /** A publish run's image-plan step: it also gathers the layers the image lane pushes. */
    static BuildPlan.Builder publish(
            BuildPlan.Builder builder, BuildPlanner.Inputs inputs, Path projectDir, Path cache) {
        return builder.stateKeys(ImagePlans.CONFIG, STATIC, ImagePushPlan.KEY)
                .extend(planStep(inputs, projectDir, cache, null, null, null, true));
    }

    private static Task planStep(
            BuildPlanner.Inputs inputs,
            Path projectDir,
            Path cache,
            @Nullable String tag,
            @Nullable String tarballArg,
            @Nullable String dockerExecutableArg,
            boolean push) {
        return Task.builder(TaskNames.IMAGE_PLAN)
                .forecast(push ? StepForecast.always("the image lane pushes the image") : StepForecast.RIDES)
                .stage(BuildStage.IMAGE)
                .requires(TaskNames.NODE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve image config");
                    JkBuild project = ctx.require(BuildPlanner.MODULE).project();
                    BuildLayout layout = ctx.require(BuildPlanner.MODULE).layout();
                    ImageTable data = ManifestImage.merge(project.image(), GlobalConfig.image());
                    if (Boolean.TRUE.equals(data.aotCache())) {
                        String refusal = "[image] aot-cache: "
                                + project.project().name() + " is a node module, not a JVM one; remove aot-cache";
                        ctx.error("image", refusal);
                        throw new IllegalStateException(refusal);
                    }
                    boolean staticSite = ImageTable.KIND_STATIC.equals(data.kind());
                    if (!staticSite && NodeImageContent.start(project, projectDir) == null) {
                        ctx.label("no image · " + project.project().name()
                                + " builds static files; set [image] kind = \"static\" for an nginx image");
                        ctx.progress(1);
                        return;
                    }
                    Path tarballPath = ImagePlans.resolveTarballPath(tarballArg, layout);
                    if (tarballPath != null) ctx.put(ImagePlans.TARBALL_PATH, tarballPath);
                    ctx.put(STATIC, staticSite);
                    ImageConfig config = config(data, project, projectDir, staticSite, tag, dockerExecutableArg);
                    String unsignable = push ? ImagePushProvenance.unsignable(config) : null;
                    if (unsignable != null) {
                        ctx.error("image", unsignable);
                        throw new IllegalStateException(unsignable);
                    }
                    ctx.put(ImagePlans.CONFIG, config);
                    if (push) {
                        NodeImageContent.Content content =
                                content(ctx, inputs, project, projectDir, staticSite, config);
                        ctx.put(
                                ImagePushPlan.KEY,
                                new ImagePushPlan(
                                        cache,
                                        null,
                                        base -> spec(cache, project, layout, config, base, content, null, true)));
                    }
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
                if (pin == null) throw new IOException("Node.js is not locked yet; run jk lock");
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
                Map.of(),
                data.labels(),
                data.registry(),
                tag != null ? tag : data.tag(),
                data.platforms(),
                null,
                dockerExe,
                null,
                false,
                !Boolean.FALSE.equals(data.referrers()),
                Boolean.TRUE.equals(data.sigstore()));
    }

    /** The layers of the node image {@code config} describes: the server with its production install, or the static files. */
    private static NodeImageContent.Content content(
            TaskContext ctx,
            BuildPlanner.Inputs inputs,
            JkBuild project,
            Path projectDir,
            boolean staticSite,
            ImageConfig config)
            throws IOException, InterruptedException {
        String declared = Objects.requireNonNull(config.base(), "image base");
        try {
            NodeImageContent.Content content = staticSite
                    ? NodeImageContent.staticSite(project, projectDir)
                    : NodeImageContent.server(
                            ctx, inputs, project, projectDir, ctx.require(BuildPlanner.NODE_HOME), declared);
            ctx.label("layers · "
                    + String.join(
                            ", ",
                            content.layers().stream()
                                    .map(NodeImageContent.Layer::name)
                                    .toList()) + " on " + declared);
            return content;
        } catch (IOException | RuntimeException e) {
            ctx.error("image", Errors.text(e));
            throw e;
        }
    }

    private static Task writeStep(BuildPlanner.Inputs inputs, Path projectDir, Path cache) {
        return Task.builder(TaskNames.WRITE_IMAGE)
                .forecast(StepForecast.always("an image push, load or tarball write is a side effect"))
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
                    JkBuild project = ctx.require(BuildPlanner.MODULE).project();
                    BuildLayout layout = ctx.require(BuildPlanner.MODULE).layout();
                    Path tarballPath = ctx.get(ImagePlans.TARBALL_PATH).orElse(null);
                    boolean staticSite = ctx.get(STATIC).orElse(false);
                    NodeImageContent.Content content = content(ctx, inputs, project, projectDir, staticSite, config);
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
                                    "worker:" + WorkerClosure.token(workerJar)),
                            (base, said) -> ImagePlans.forkWorker(
                                    workerJar,
                                    project,
                                    spec(cache, project, layout, config, base, content, tarballPath, false),
                                    said));
                })
                .build();
    }

    /** The worker spec for a layered node image: a tarball, else a push when {@code push}, else a daemon load. */
    static SpecWriter spec(
            Path cache,
            JkBuild project,
            BuildLayout layout,
            ImageConfig config,
            String base,
            NodeImageContent.Content content,
            @Nullable Path tarballPath,
            boolean push) {
        boolean daemonMode = tarballPath == null && !push;
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_IMAGE, null, "jk-image-builder")
                .configString("kind", "node")
                .configString("artifact", project.project().name())
                .configString("version", project.project().version())
                .configString("mode", tarballPath != null ? "tarball" : daemonMode ? "daemon" : "push")
                .configString("base", base)
                .configString("jkCache", cache.toAbsolutePath().toString());
        ImageCredentials.write(sw, base, config, project, tarballPath == null && !daemonMode, layout.moduleRoot());
        if (!config.referrers()) {
            sw.configBool("referrers", false);
        } else if (tarballPath == null && !daemonMode) {
            ImagePushProvenance.write(sw, config, layout.moduleRoot());
        }
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
