// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.ManifestImage;
import cc.jumpkick.config.SnapshotQualifier;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ReleaseDeliverables;
import cc.jumpkick.model.ImageTable;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.PlannerNative;
import cc.jumpkick.runtime.Tail;
import cc.jumpkick.runtime.base.ImageCredentials;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.WorkerClosure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk image} of a module with {@code [image] kind = "native"}: its {@code [native]}
 * executable alone on a distroless base, one image per platform whose executable exists, under an
 * OCI image index when there are several. This host's executable is the one the build links; another
 * platform's is the file the release lane names, {@code <name>-<version>-<os>-<arch>}, in {@code
 * target/}.
 */
final class NativeImagePlans {

    /** The base of a dynamically linked executable: glibc, zlib and libgcc. */
    static final String DYNAMIC_BASE = "gcr.io/distroless/cc-debian13";

    /** The base of a {@code --static} executable: no libc, only CA certificates and time zones. */
    static final String STATIC_BASE = "gcr.io/distroless/static-debian13";

    /** The non-root user of the distroless images. */
    static final String NONROOT = "65532";

    /** The directory the executable lies in, inside the image. */
    static final String APP_DIR = "/app";

    /** The executables the image carries, by {@code os/arch} platform. */
    static final BuildPlanKey<Executables> EXECUTABLES =
            BuildPlanKey.scalar("image-native-executables", Executables.class);

    private NativeImagePlans() {}

    /** Whether {@code project}'s image is its native executable's. */
    static boolean isNative(JkBuild project) {
        return ImageTable.KIND_NATIVE.equals(
                ManifestImage.merge(project.image(), GlobalConfig.image()).kind());
    }

    /**
     * The executable of each platform the image is built for, in order, and the platforms left out
     * because their executable is not there.
     */
    record Executables(Map<String, Path> found, List<String> skipped) {
        Executables {
            found = Collections.unmodifiableMap(new LinkedHashMap<>(found));
            skipped = List.copyOf(skipped);
        }

        /** The one line naming what was left out, or null when nothing was. */
        @Nullable
        String skipNote() {
            if (skipped.isEmpty() || found.isEmpty()) return null;
            return "[image] no executable for " + String.join(", ", skipped) + "; built "
                    + String.join(", ", found.keySet());
        }

        /**
         * These executables as a daemon can load them: one platform, {@code host}'s when it is among
         * them, else the first.
         */
        Executables forDaemon(String host) {
            if (found.size() < 2) return this;
            String keep =
                    found.containsKey(host) ? host : found.keySet().iterator().next();
            return new Executables(Map.of(keep, found.get(keep)), skipped);
        }
    }

    /** {@code linux/amd64} for {@code linux-x86_64}: a release platform as an image names it. */
    static String imagePlatform(String releasePlatform) {
        int dash = releasePlatform.indexOf('-');
        String os = releasePlatform.substring(0, dash);
        String arch = releasePlatform.substring(dash + 1);
        return os + "/"
                + switch (arch) {
                    case "x86_64" -> "amd64";
                    case "aarch64" -> "arm64";
                    default -> arch;
                };
    }

    /** {@code linux-x86_64} for {@code linux/amd64}: an image platform as a release names it. */
    static String releasePlatform(String imagePlatform) {
        int slash = imagePlatform.indexOf('/');
        if (slash <= 0 || slash == imagePlatform.length() - 1) {
            throw new IllegalArgumentException("[image] platforms: `" + imagePlatform + "` is not os/arch");
        }
        String rest = imagePlatform.substring(slash + 1);
        int variant = rest.indexOf('/');
        String arch = variant < 0 ? rest : rest.substring(0, variant);
        return ReleasePlatform.os(imagePlatform.substring(0, slash)) + "-" + ReleasePlatform.arch(arch);
    }

    /**
     * The executables of the {@code declared} platforms ({@code [image] platforms}), or of {@code
     * host}'s platform when none is declared. {@code host}'s is the one the build links; another's
     * is the release-named file beside it.
     */
    static Executables executables(BuildLayout layout, JkBuild build, List<String> declared, String host) {
        List<String> targets = new ArrayList<>(declared);
        if (targets.isEmpty()) {
            if (!host.startsWith("linux-")) {
                throw new IllegalArgumentException("[image] a native image runs a linux executable and this host"
                        + " builds a " + host.substring(0, host.indexOf('-')) + " one; set [image] platforms");
            }
            targets.add(imagePlatform(host));
        }
        Map<String, Path> found = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (String platform : targets) {
            if (!platform.toLowerCase(Locale.ROOT).startsWith("linux/")) {
                throw new IllegalArgumentException(
                        "[image] platforms: a native image runs linux executables, not " + platform);
            }
            String release = releasePlatform(platform);
            Path staged = staged(layout, build, release);
            Path file =
                    release.equals(host) && Files.isRegularFile(layout.nativeBinary()) ? layout.nativeBinary() : staged;
            if (Files.isRegularFile(file)) found.put(platform, file);
            else skipped.add(platform);
        }
        return new Executables(found, skipped);
    }

    /**
     * Where another platform's executable lies: {@code target/} under the name the release gives it,
     * which carries the declared version; a snapshot build also takes the file named for itself.
     */
    static Path staged(BuildLayout layout, JkBuild build, String platform) {
        String name = ReleaseDeliverables.nativeName(layout, build, platform);
        Path exact = layout.artifactDir().resolve(name);
        String version = layout.version();
        if (Files.isRegularFile(exact) || !version.endsWith(SnapshotQualifier.SUFFIX)) return exact;
        String declared = version.substring(0, version.length() - SnapshotQualifier.SUFFIX.length());
        Path released = layout.artifactDir().resolve(name.replace("-" + version + "-", "-" + declared + "-"));
        return Files.isRegularFile(released) ? released : exact;
    }

    /**
     * True when the image needs this host's executable: no platform is declared, or one declared
     * platform is this host's.
     */
    static boolean needsHostExecutable(List<String> declared, String host) {
        if (declared.isEmpty()) return host.startsWith("linux-");
        for (String platform : declared) {
            try {
                if (releasePlatform(platform).equals(host)) return true;
            } catch (IllegalArgumentException e) {
                // a malformed platform is refused by the image-plan step, with its own words
            }
        }
        return false;
    }

    /**
     * The default base for {@code build}'s executable: {@link #STATIC_BASE} for a {@code --static}
     * one, else {@link #DYNAMIC_BASE}, which carries the glibc a default or {@code
     * --static-nolibc} executable links against.
     */
    static String defaultBase(JkBuild build) {
        List<String> args =
                build.nativeConfigOpt().map(JkBuild.NativeConfig::args).orElse(List.of());
        return args.contains("--static") ? STATIC_BASE : DYNAMIC_BASE;
    }

    /** The executable's file name in the image: the {@code [native] name}, else the artifact id. */
    static String executableName(BuildLayout layout, JkBuild build) {
        return build.nativeConfigOpt()
                .map(JkBuild.NativeConfig::name)
                .filter(n -> !n.isBlank())
                .orElse(layout.artifact());
    }

    /**
     * The image config: {@code [image]} over the user-global table, on {@link #defaultBase} as user
     * {@value #NONROOT} unless the table says otherwise, for the {@code platforms} found.
     */
    static ImageConfig config(
            ImageTable data,
            JkBuild build,
            List<String> platforms,
            @Nullable String tag,
            @Nullable String dockerExecutableArg) {
        String base = data.base() != null && !data.base().isBlank() ? data.base() : defaultBase(build);
        String user = data.user() != null && !data.user().isBlank() ? data.user() : NONROOT;
        String dockerExe = dockerExecutableArg != null ? dockerExecutableArg : data.dockerExecutable();
        if (dockerExe == null || dockerExe.isBlank()) dockerExe = ImagePlans.detectDockerExecutable();
        return new ImageConfig(
                base,
                data.name(),
                user,
                data.ports(),
                data.env(),
                Map.of(),
                data.labels(),
                data.registry(),
                tag != null ? tag : data.tag(),
                platforms,
                null,
                dockerExe,
                null,
                false,
                !Boolean.FALSE.equals(data.referrers()),
                Boolean.TRUE.equals(data.sigstore()));
    }

    /** Why {@code data} cannot describe a native image of {@code build}, or null. */
    static @Nullable String refusal(ImageTable data, JkBuild build) {
        String name = build.project().name();
        if (build.nativeConfigOpt().isEmpty()) {
            return "[image] kind = \"native\": " + name + " has no [native] table";
        }
        if (Boolean.TRUE.equals(data.aotCache())) {
            return "[image] aot-cache: " + name + " ships a native executable, not a JVM";
        }
        if (data.dockerFile() != null && !data.dockerFile().isBlank()) {
            return "[image] docker-file and kind = \"native\" both say what the image holds";
        }
        return null;
    }

    /**
     * Add the native image's steps: the executable's link when this host's is needed and the plan
     * does not already link it, then image-plan and write-image.
     */
    static Tail tail(
            JkBuild unit,
            @Nullable Path graalHome,
            @Nullable String tag,
            @Nullable String tarballArg,
            @Nullable String dockerExecutableArg) {
        return (plan, in) -> {
            boolean linked = link(plan, in, unit, graalHome);
            plan.stateKeys(ImagePlans.CONFIG, ImagePlans.TARBALL_PATH, ImagePlans.IMAGE_REF, EXECUTABLES)
                    .extend(planStep(in, linked, tag, tarballArg, dockerExecutableArg, false))
                    .extend(writeStep(in));
        };
    }

    /** A publish run's image-plan step, which leaves the push for the image lane. */
    static void publish(BuildPlan.Builder plan, BuildPlanner.Inputs in, JkBuild unit, @Nullable Path graalHome) {
        boolean linked = link(plan, in, unit, graalHome);
        plan.stateKeys(ImagePlans.CONFIG, EXECUTABLES, ImagePushPlan.KEY)
                .extend(planStep(in, linked, null, null, null, true));
    }

    /** Add the native-image step when the image needs this host's executable; true when the plan links it. */
    private static boolean link(
            BuildPlan.Builder plan, BuildPlanner.Inputs in, JkBuild unit, @Nullable Path graalHome) {
        if (plan.stepNames().contains(TaskNames.NATIVE_IMAGE)) return true;
        ImageTable data = ManifestImage.merge(unit.image(), GlobalConfig.image());
        if (unit.nativeConfigOpt().isEmpty() || !needsHostExecutable(data.platforms(), ReleasePlatform.current())) {
            return false;
        }
        plan.extend(PlannerNative.nativeStep(
                in.dir(),
                in.cache(),
                in.lockFile(),
                in.jdksDir(),
                graalHome,
                NativePlans.resolveMain(in.buildFile(), null),
                List.of(),
                /*allowShared*/ false));
        return true;
    }

    private static Task planStep(
            BuildPlanner.Inputs in,
            boolean linked,
            @Nullable String tag,
            @Nullable String tarballArg,
            @Nullable String dockerExecutableArg,
            boolean push) {
        Path cache = in.cache();
        return Task.builder(TaskNames.IMAGE_PLAN)
                .forecast(push ? StepForecast.always("the image lane pushes the image") : StepForecast.RIDES)
                .stage(BuildStage.IMAGE)
                .requires(linked ? TaskNames.NATIVE_IMAGE : TaskNames.PACKAGE_JAR)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("resolve image config");
                    JkBuild project = ctx.require(BuildPlanner.MODULE).project();
                    BuildLayout layout = ctx.require(BuildPlanner.MODULE).layout();
                    ImageTable data = ManifestImage.merge(project.image(), GlobalConfig.image());
                    String refused = refusal(data, project);
                    if (refused != null) {
                        ctx.error("image", refused);
                        throw new IllegalStateException(refused);
                    }
                    Path tarballPath = ImagePlans.resolveTarballPath(tarballArg, layout);
                    String host = ReleasePlatform.current();
                    Executables found;
                    try {
                        found = executables(layout, project, data.platforms(), host);
                    } catch (IllegalArgumentException e) {
                        ctx.error("image", Errors.text(e));
                        throw e;
                    }
                    if (found.found().isEmpty()) {
                        String missing = "[image] no native executable for " + String.join(", ", found.skipped())
                                + " in " + layout.artifactDir().getFileName() + "/";
                        ctx.error("image", missing);
                        throw new IllegalStateException(missing);
                    }
                    String note = found.skipNote();
                    if (note != null) ctx.warn("image-platforms", note);
                    if (tarballPath == null && !push) {
                        Executables one = found.forDaemon(imagePlatform(host));
                        if (one.found().size() < found.found().size()) {
                            ctx.warn(
                                    "image-platforms",
                                    "[image] a daemon holds one platform: loaded "
                                            + one.found().keySet().iterator().next() + "; --tarball keeps all");
                        }
                        found = one;
                    }
                    if (tarballPath != null) ctx.put(ImagePlans.TARBALL_PATH, tarballPath);
                    ImageConfig config =
                            config(data, project, List.copyOf(found.found().keySet()), tag, dockerExecutableArg);
                    String unsignable = push ? ImagePushProvenance.unsignable(config) : null;
                    if (unsignable != null) {
                        ctx.error("image", unsignable);
                        throw new IllegalStateException(unsignable);
                    }
                    ctx.put(ImagePlans.CONFIG, config);
                    ctx.put(EXECUTABLES, found);
                    if (push) {
                        Executables pushed = found;
                        ctx.put(
                                ImagePushPlan.KEY,
                                new ImagePushPlan(
                                        cache,
                                        null,
                                        base -> spec(cache, project, layout, config, base, pushed, null, true)));
                    }
                    ctx.label("image of " + executableName(layout, project) + " for "
                            + String.join(", ", found.found().keySet()));
                    ctx.progress(1);
                })
                .build();
    }

    private static Task writeStep(BuildPlanner.Inputs in) {
        Path cache = in.cache();
        return Task.builder(TaskNames.WRITE_IMAGE)
                .forecast(StepForecast.always("an image push, load or tarball write is a side effect"))
                .stage(BuildStage.IMAGE)
                .kind(TaskKind.IO)
                .requires(TaskNames.IMAGE_PLAN)
                .ticks(1)
                .execute(ctx -> {
                    JkBuild project = ctx.require(BuildPlanner.MODULE).project();
                    BuildLayout layout = ctx.require(BuildPlanner.MODULE).layout();
                    ImageConfig config = ctx.require(ImagePlans.CONFIG);
                    Executables found = ctx.require(EXECUTABLES);
                    Path tarballPath = ctx.get(ImagePlans.TARBALL_PATH).orElse(null);
                    Path workerJar = PluginJar.IMAGE_BUILDER.locate(JkStores.storeCas());
                    ImageWrite.restoreOrBuild(
                            ctx,
                            project,
                            layout,
                            config,
                            cache,
                            tarballPath,
                            base -> tokens(layout, project, config, base, found, workerJar),
                            (base, said) -> ImagePlans.forkWorker(
                                    workerJar,
                                    project,
                                    spec(cache, project, layout, config, base, found, tarballPath, false),
                                    said));
                })
                .build();
    }

    /** Everything the image is a function of, given the resolved {@code base}. */
    private static List<String> tokens(
            BuildLayout layout, JkBuild project, ImageConfig config, String base, Executables found, Path workerJar)
            throws IOException {
        List<String> tokens = new ArrayList<>();
        for (Map.Entry<String, Path> e : found.found().entrySet()) {
            tokens.add("native:" + e.getKey() + "=" + ClasspathFingerprint.entry(e.getValue()));
        }
        tokens.add("path:" + APP_DIR + "/" + executableName(layout, project));
        tokens.add("base:" + base);
        tokens.add("cfg:" + ImagePlans.imageConfigToken(config));
        tokens.add("ref:"
                + config.targetReference(
                        project.project().name(), project.project().version()));
        tokens.add("worker:" + WorkerClosure.token(workerJar));
        return tokens;
    }

    /** The worker spec for a native image: a tarball, else a push when {@code push}, else a daemon load. */
    static SpecWriter spec(
            Path cache,
            JkBuild project,
            BuildLayout layout,
            ImageConfig config,
            String base,
            Executables found,
            @Nullable Path tarballPath,
            boolean push) {
        boolean daemonMode = tarballPath == null && !push;
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_IMAGE, null, "jk-image-builder")
                .configString("kind", ImageTable.KIND_NATIVE)
                .configString("artifact", project.project().name())
                .configString("version", project.project().version())
                .configString("mode", tarballPath != null ? "tarball" : daemonMode ? "daemon" : "push")
                .configString("base", base)
                .configString("jkCache", cache.toAbsolutePath().toString())
                .configString("executablePath", APP_DIR + "/" + executableName(layout, project));
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
        List<String> executables = new ArrayList<>();
        for (Map.Entry<String, Path> e : found.found().entrySet()) {
            executables.add(e.getKey() + "\t" + e.getValue().toAbsolutePath());
        }
        sw.configList("executables", executables);
        return sw;
    }
}
