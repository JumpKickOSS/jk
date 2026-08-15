// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.image.ImageConfig;
import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.DockerDaemonImage;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainer;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.TarImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FilePermissions;
import com.google.cloud.tools.jib.api.buildplan.Platform;
import com.google.cloud.tools.jib.api.buildplan.Port;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Jib-core OCI image builder: base image, layered jars under {@code /app/}, {@link ImageConfig}
 * metadata, registry push or local tarball, deterministic timestamps.
 */
public final class ImageBuilder {

    private ImageBuilder() {}

    public record Plan(
            ImageConfig config,
            String artifact,
            String version,
            String mainClass,
            Path mainJar,
            List<Path> dependencyJars,
            List<Path> snapshotJars,
            Path classesDir,
            /**
             * Coordinate-derived file name per dependency jar. jk serves the runtime classpath from
             * the content-addressed store, so a jar's own path is its digest — shipping that into
             * an image leaves a lib/ directory nobody, and no scanner, can read.
             */
            Map<Path, String> jarNames,
            /**
             * A self-contained runnable tree the packager produced (Quarkus's {@code quarkus-app/}),
             * or null. When set it is the whole application: shipped as-is and launched with
             * {@code java -jar appJar} from its own directory.
             */
            Path appDir,
            /** The jar to run inside {@link #appDir}. */
            String appJar) {

        /** Without coordinate names: jars keep their on-disk file name. */
        public Plan(
                ImageConfig config,
                String artifact,
                String version,
                String mainClass,
                Path mainJar,
                List<Path> dependencyJars,
                List<Path> snapshotJars,
                Path classesDir) {
            this(
                    config,
                    artifact,
                    version,
                    mainClass,
                    mainJar,
                    dependencyJars,
                    snapshotJars,
                    classesDir,
                    Map.of(),
                    null,
                    null);
        }

        /** The name this jar should carry in the image. */
        public String nameOf(Path jar) {
            String named = jarNames.get(jar);
            return named != null && !named.isBlank() ? named : jar.getFileName().toString();
        }

        /** With coordinate names but no packager-produced tree. */
        public Plan(
                ImageConfig config,
                String artifact,
                String version,
                String mainClass,
                Path mainJar,
                List<Path> dependencyJars,
                List<Path> snapshotJars,
                Path classesDir,
                Map<Path, String> jarNames) {
            this(
                    config,
                    artifact,
                    version,
                    mainClass,
                    mainJar,
                    dependencyJars,
                    snapshotJars,
                    classesDir,
                    jarNames,
                    null,
                    null);
        }

        /** True when the packager handed over a complete runnable tree. */
        public boolean hasAppTree() {
            return appDir != null && appJar != null && !appJar.isBlank();
        }

        public Plan {
            Objects.requireNonNull(config, "config");
            jarNames = jarNames == null ? Map.of() : Map.copyOf(jarNames);
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(mainClass, "mainClass");
            Objects.requireNonNull(mainJar, "mainJar");
            dependencyJars = List.copyOf(dependencyJars);
            snapshotJars = snapshotJars == null ? List.of() : List.copyOf(snapshotJars);
            // classesDir nullable: set = classes-dir layout (Spring Boot layer mapping),
            // null = classic jar-on-classpath layout.
        }

        /** Back-compat constructor: classic layout (main jar + one dependency layer). */
        public Plan(
                ImageConfig config,
                String artifact,
                String version,
                String mainClass,
                Path mainJar,
                List<Path> dependencyJars) {
            this(config, artifact, version, mainClass, mainJar, dependencyJars, List.of(), null);
        }
    }

    public record Result(String imageReference, String digest) {}

    /** Push to a registry. */
    public static Result pushToRegistry(Plan plan) throws IOException, InterruptedException {
        try {
            JibContainer container = run(plan, Containerizer.to(registryTarget(plan)));
            return new Result(
                    plan.config().targetReference(plan.artifact(), plan.version()),
                    container.getDigest().toString());
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /**
     * Load the image directly into the local Docker/Podman daemon. {@code dockerExecutable} is the
     * resolved CLI path (e.g. {@code "docker"} or {@code "podman"}, or an absolute path); pass
     * {@code null} to let Jib auto-detect via {@code PATH}.
     */
    public static Result loadToLocalDaemon(Plan plan, Path dockerExecutable) throws IOException, InterruptedException {
        try {
            DockerDaemonImage target =
                    DockerDaemonImage.named(plan.config().targetReference(plan.artifact(), plan.version()));
            if (dockerExecutable != null) target = target.setDockerExecutable(dockerExecutable);
            JibContainer container = run(plan, Containerizer.to(target));
            return new Result(
                    plan.config().targetReference(plan.artifact(), plan.version()),
                    container.getDigest().toString());
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /** Build to a local OCI tarball ({@code --tarball} mode). */
    public static Result writeToTarball(Plan plan, Path tarball) throws IOException, InterruptedException {
        try {
            JibContainer container = run(
                    plan,
                    Containerizer.to(TarImage.at(tarball)
                            .named(plan.config().targetReference(plan.artifact(), plan.version()))));
            return new Result(
                    plan.config().targetReference(plan.artifact(), plan.version()),
                    container.getDigest().toString());
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /**
     * Ship the trained tree verbatim at {@code /app}, timestamps included. The archive validates
     * each entry by size and modification time, so the bytes that were trained against and the
     * bytes that ship have to agree on both.
     */
    /** Ship {@code src} under {@code /app}: a directory verbatim, or a single file at {@code as}. */
    private static FileEntriesLayer treeLayer(Path src, String as) throws IOException {
        FileEntriesLayer.Builder layer = FileEntriesLayer.builder();
        if (Files.isRegularFile(src)) {
            layer.addEntry(
                    src,
                    AbsoluteUnixPath.get(AotCacheTrainer.APP_DIR + "/" + (as == null ? src.getFileName() : as)),
                    FilePermissions.DEFAULT_FILE_PERMISSIONS,
                    AotCacheTrainer.LAYER_TIME.toInstant());
            return layer.build();
        }
        try (var walk = Files.walk(src)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                layer.addEntry(
                        file,
                        AbsoluteUnixPath.get(AotCacheTrainer.APP_DIR + "/"
                                + src.relativize(file).toString().replace('\\', '/')),
                        FilePermissions.DEFAULT_FILE_PERMISSIONS,
                        AotCacheTrainer.LAYER_TIME.toInstant());
            }
        }
        return layer.build();
    }

    /** Only the files staged before training — the cache ships as its own layer. */
    private static FileEntriesLayer stagedTreeLayer(AotCacheTrainer.Result aot) throws IOException {
        Path root = aot.stagingRoot();
        FileEntriesLayer.Builder layer = FileEntriesLayer.builder();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (!aot.stagedFiles().contains(rel)) continue; // training-run droppings
                layer.addEntry(
                        file,
                        AbsoluteUnixPath.get(AotCacheTrainer.APP_DIR + "/" + rel),
                        FilePermissions.DEFAULT_FILE_PERMISSIONS,
                        AotCacheTrainer.LAYER_TIME.toInstant());
            }
        }
        return layer.build();
    }

    /**
     * Entrypoint for a packager-produced app tree: {@code java [-XX:AOTCache=app.aot] -jar
     * <appJar>}. No lock-derived classpath — the tree is the whole program (JK-1722).
     */
    static List<String> appTreeEntrypoint(Plan plan, boolean aotCache) {
        List<String> entry = new ArrayList<>();
        entry.add("java");
        if (aotCache) entry.add("-XX:AOTCache=" + AotCacheTrainer.CACHE_FILE);
        entry.add("-jar");
        entry.add(plan.appJar());
        return entry;
    }

    /** Dependency jars at {@code /app/libs}, named by coordinate rather than by CAS digest. */
    private static FileEntriesLayer namedJarLayer(Plan plan, List<Path> jars) {
        FileEntriesLayer.Builder layer = FileEntriesLayer.builder();
        Set<String> seen = new HashSet<>();
        for (Path jar : jars) {
            String name = plan.nameOf(jar);
            // Two entries at one path would extract as "last tar entry wins" — a jar silently
            // missing from the runtime classpath. The engine disambiguates names; this guards
            // the fallback (raw file names) and any future naming drift.
            if (!seen.add(name)) {
                throw new IllegalStateException("duplicate image jar name /app/libs/" + name);
            }
            layer.addEntry(jar, AbsoluteUnixPath.get("/app/libs/" + name));
        }
        return layer.build();
    }

    private static JibContainer run(Plan plan, Containerizer containerizer)
            throws IOException, InterruptedException, InvalidImageReferenceException {
        ImageConfig cfg = plan.config();
        JibContainerBuilder builder;
        try {
            builder = Jib.from(RegistryImage.named(cfg.base()));
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid base image: " + cfg.base(), e);
        }

        // A packager-produced tree is the entire application. Shipping it verbatim is the only
        // layout that runs — Quarkus enters through its own bootstrap and loads lib/main with its
        // own class loader, so a lock-derived classpath describes a different program.
        if (plan.hasAppTree()) {
            builder = builder.addFileEntriesLayer(treeLayer(plan.appDir(), null));
            builder = builder.setWorkingDirectory(AbsoluteUnixPath.get(AotCacheTrainer.APP_DIR));
            boolean aot = cfg.aotCache();
            if (aot) {
                AotCacheTrainer.Result trained = AotCacheTrainer.train(
                        plan, plan.mainJar().getParent(), msg -> System.err.println("jk: " + msg));
                builder = builder.addFileEntriesLayer(treeLayer(trained.cache(), AotCacheTrainer.CACHE_FILE));
            }
            builder = builder.setEntrypoint(appTreeEntrypoint(plan, aot));
            return finish(builder, plan, containerizer);
        }

        // AOT cache: the trainer stages the runnable layout at /app, trains, and hands back the
        // staged manifest + cache. The staged tree IS the application — shipping layers 1-3 as
        // well would double every byte, and shipping the whole post-training staging root would
        // embed whatever the app wrote during the record run (JK-1758).
        AotCacheTrainer.Result aot = null;
        String appClasspath = null;
        if (cfg.aotCache()) {
            String blocked = AotCacheTrainer.unsupportedReason(plan);
            if (blocked != null) {
                throw new IOException("[image] aot-cache = true, but " + blocked);
            }
            aot = AotCacheTrainer.train(plan, plan.mainJar().getParent(), msg -> System.err.println("jk: " + msg));
            builder = builder.addFileEntriesLayer(stagedTreeLayer(aot));
            builder = builder.addFileEntriesLayer(treeLayer(aot.cache(), AotCacheTrainer.CACHE_FILE));
            builder = builder.setWorkingDirectory(AbsoluteUnixPath.get(AotCacheTrainer.APP_DIR));
        } else {
            // Layer 1 — release dependency jars (change least often).
            if (!plan.dependencyJars().isEmpty()) {
                builder = builder.addFileEntriesLayer(namedJarLayer(plan, plan.dependencyJars()));
            }
            // Layer 2 — SNAPSHOT dependency jars (their own layer: they churn while releases
            // don't, so a snapshot bump never invalidates the big release-deps layer).
            if (!plan.snapshotJars().isEmpty()) {
                builder = builder.addFileEntriesLayer(namedJarLayer(plan, plan.snapshotJars()));
            }
            // Layer 3 — the application: either exploded classes (Boot layer mapping — the
            // most-frequently-changing bytes ride the smallest layer) or the classic main jar.
            if (plan.classesDir() != null) {
                builder = builder.addFileEntriesLayer(classesLayer(plan.classesDir()));
                appClasspath = "/app/classes:/app/libs/*";
            } else {
                builder = builder.addLayer(List.of(plan.mainJar()), AbsoluteUnixPath.get("/app/classpath"));
                appClasspath = "/app/classpath/*:/app/libs/*";
            }
        }

        // Entrypoint: java -cp <app classpath> <main>
        List<String> entrypoint = new ArrayList<>();
        entrypoint.add("java");
        if (!cfg.env().isEmpty()) {
            // JAVA_OPTS is the conventional hook; values are joined with spaces.
            String javaOpts = cfg.env().get("JAVA_OPTS");
            if (javaOpts != null && !javaOpts.isBlank()) {
                for (String token : javaOpts.trim().split("\\s+")) entrypoint.add(token);
            }
        }
        if (aot != null) {
            entrypoint.add("-XX:AOTCache=" + AotCacheTrainer.CACHE_FILE);
            entrypoint.addAll(aot.runArgs());
        } else {
            entrypoint.add("-cp");
            entrypoint.add(appClasspath);
            entrypoint.add(plan.mainClass());
        }
        builder = builder.setEntrypoint(entrypoint);
        return finish(builder, plan, containerizer);
    }

    /** Everything after the entrypoint: identity, ports, env, labels, platforms, and the build. */
    private static JibContainer finish(JibContainerBuilder builder, Plan plan, Containerizer containerizer)
            throws IOException, InterruptedException, InvalidImageReferenceException {
        ImageConfig cfg = plan.config();

        if (cfg.user() != null && !cfg.user().isBlank()) {
            builder = builder.setUser(cfg.user());
        }
        if (!cfg.ports().isEmpty()) {
            Set<Port> ports = new HashSet<>();
            for (int p : cfg.ports()) ports.add(Port.tcp(p));
            builder = builder.setExposedPorts(ports);
        }
        if (!cfg.env().isEmpty()) {
            builder = builder.setEnvironment(cfg.env());
        }
        if (!cfg.labels().isEmpty()) {
            builder = builder.setLabels(cfg.labels());
        }
        if (!cfg.platforms().isEmpty()) {
            Set<Platform> platforms = new HashSet<>();
            for (String p : cfg.platforms()) {
                int slash = p.indexOf('/');
                if (slash <= 0 || slash >= p.length() - 1) {
                    throw new IOException("invalid platform `" + p + "` (expect os/arch)");
                }
                platforms.add(new Platform(p.substring(slash + 1), p.substring(0, slash)));
            }
            builder = builder.setPlatforms(platforms);
        }
        // Creation time = mtime of the primary payload jar. If the jar was restored
        // from the CAS (cache hit), its mtime is preserved by hard-link / COPY_ATTRIBUTES,
        // so the timestamp reflects "when this content was first produced" — stable across
        // repeated builds of unchanged code, accurate when code changes, and git-independent.
        Instant creationTime;
        try {
            creationTime = Files.getLastModifiedTime(plan.mainJar()).toInstant();
        } catch (IOException ignored) {
            creationTime = Instant.now();
        }
        builder = builder.setCreationTime(creationTime);

        try {
            return builder.containerize(containerizer);
        } catch (RegistryException | ExecutionException | CacheDirectoryCreationException e) {
            throw new IOException("image build failed: " + e.getMessage(), e);
        }
    }

    /**
     * The exploded app-classes layer at {@code /app/classes}, with jk's freshness stamps
     * ({@code .jstamp}/{@code .kstamp}/{@code .test-stamp}) filtered out — build-host metadata,
     * never image content. Files sorted for deterministic layer bytes.
     */
    private static com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer classesLayer(Path classesDir)
            throws IOException {
        var layer = com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer.builder()
                .setName("classes");
        AbsoluteUnixPath target = AbsoluteUnixPath.get("/app/classes");
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(classesDir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> classesDir.relativize(p).toString()));
        for (Path file : files) {
            String rel = classesDir.relativize(file).toString().replace(File.separatorChar, '/');
            if (rel.endsWith(".jstamp") || rel.endsWith(".kstamp") || rel.endsWith(".test-stamp")) continue;
            layer.addEntry(file, target.resolve(rel));
        }
        return layer.build();
    }

    private static RegistryImage registryTarget(Plan plan) throws InvalidImageReferenceException {
        return RegistryImage.named(plan.config().targetReference(plan.artifact(), plan.version()));
    }

    /** Convert parsed HOCON data into an {@link ImageConfig}. */
    public static ImageConfig fromParsed(Map<String, Object> envMap, ImageConfig defaults) {
        return defaults; // placeholder — see ImageCommand for the bridge.
    }
}
