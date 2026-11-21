// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.image.ImageConfig;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.DockerDaemonImage;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.JibContainer;
import com.google.cloud.tools.jib.api.JibContainerBuilder;
import com.google.cloud.tools.jib.api.TarImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FilePermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An image of files rather than a JVM program: named layers, each a directory or a file placed at
 * a fixed image path, plus an entrypoint and a working directory. A node server and an nginx image
 * of a node build's static output are both this shape; the engine decides the layers.
 */
public final class LayeredImage {

    private LayeredImage() {}

    /** One layer: {@code src} (a directory, copied whole, or a file) at the image path {@code dest}. */
    public record Layer(String name, Path src, String dest) {
        public Layer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(src, "src");
            Objects.requireNonNull(dest, "dest");
        }
    }

    /**
     * What to build. {@code entrypoint} empty keeps the base image's own; {@code workingDir} null
     * keeps the base's.
     */
    public record Plan(
            ImageConfig config,
            String artifact,
            String version,
            List<Layer> layers,
            List<String> entrypoint,
            @Nullable String workingDir) {
        public Plan {
            Objects.requireNonNull(config, "config");
            layers = List.copyOf(layers);
            entrypoint = List.copyOf(entrypoint);
        }

        String reference() {
            return config.targetReference(artifact, version);
        }
    }

    /** The layer of a node server's installed {@code node_modules}, kept across builds. */
    static final String DEPENDENCIES = "dependencies";

    /**
     * Build to a local OCI tarball. {@code cacheRoot} is jk's cache root, where the dependencies
     * layer is kept across builds; null keeps nothing.
     */
    public static ImageBuilder.Result writeToTarball(
            Plan plan, Path tarball, @Nullable Path cacheRoot, RegistryAuth auth)
            throws IOException, InterruptedException {
        try {
            return result(
                    plan, run(plan, Containerizer.to(TarImage.at(tarball).named(plan.reference())), cacheRoot, auth));
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /** Load into the local Docker/Podman daemon; {@code dockerExecutable} null lets Jib find it. */
    public static ImageBuilder.Result loadToLocalDaemon(
            Plan plan, @Nullable Path dockerExecutable, @Nullable Path cacheRoot, RegistryAuth auth)
            throws IOException, InterruptedException {
        try {
            DockerDaemonImage target = DockerDaemonImage.named(plan.reference());
            if (dockerExecutable != null) target = target.setDockerExecutable(dockerExecutable);
            return result(plan, run(plan, Containerizer.to(target), cacheRoot, auth));
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /** Push to the registry the config names. */
    public static ImageBuilder.Result pushToRegistry(Plan plan, @Nullable Path cacheRoot, RegistryAuth auth)
            throws IOException, InterruptedException {
        try {
            return result(plan, run(plan, Containerizer.to(auth.target(plan.reference())), cacheRoot, auth));
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    private static ImageBuilder.Result result(Plan plan, JibContainer container) {
        return new ImageBuilder.Result(plan.reference(), container.getDigest().toString());
    }

    /**
     * The image, its {@link #DEPENDENCIES} layer kept under {@code cacheRoot} by its files'
     * identities. The other layers are copied fresh on every build, so keeping them would only
     * crowd out kept layers that can be used again.
     */
    private static JibContainer run(Plan plan, Containerizer containerizer, @Nullable Path cacheRoot, RegistryAuth auth)
            throws IOException, InterruptedException, InvalidImageReferenceException {
        JibContainerBuilder builder = Jib.from(auth.base(ImageBuilder.baseOf(plan.config())));
        List<KeptLayer> kept = new ArrayList<>();
        for (Layer layer : plan.layers()) {
            FileEntriesLayer entries = layer(layer);
            if (cacheRoot != null && layer.name().equals(DEPENDENCIES)) {
                entries = ImageBuilder.kept(KeptLayer.byIdentity(cacheRoot, entries), entries, kept);
            }
            builder = builder.addFileEntriesLayer(entries);
        }
        if (!plan.entrypoint().isEmpty()) builder = builder.setEntrypoint(plan.entrypoint());
        if (plan.workingDir() != null) builder = builder.setWorkingDirectory(AbsoluteUnixPath.get(plan.workingDir()));
        return ImageBuilder.finish(builder, plan.config(), containerizer, auth, kept);
    }

    /**
     * {@code layer} as Jib entries, sorted, every one at the pinned layer time. Symbolic links are
     * not followed, and npm's {@code .bin} link farms are left out: an image runs {@code node} on
     * files, never a package's shell shim.
     */
    static FileEntriesLayer layer(Layer layer) throws IOException {
        FileEntriesLayer.Builder b = FileEntriesLayer.builder().setName(layer.name());
        String dest = layer.dest().endsWith("/")
                ? layer.dest().substring(0, layer.dest().length() - 1)
                : layer.dest();
        if (Files.isRegularFile(layer.src())) {
            b.addEntry(
                    layer.src(),
                    AbsoluteUnixPath.get(dest),
                    permissions(layer.src()),
                    AotCacheTrainer.LAYER_TIME.toInstant());
            return b.build();
        }
        List<Path> files = new ArrayList<>();
        PathUtil.forEachRegularFile(
                layer.src(), dir -> ".bin".equals(String.valueOf(dir.getFileName())), (file, attrs) -> files.add(file));
        files.sort(null);
        for (Path file : files) {
            String rel = layer.src().relativize(file).toString().replace('\\', '/');
            b.addEntry(
                    file,
                    AbsoluteUnixPath.get(dest + "/" + rel),
                    permissions(file),
                    AotCacheTrainer.LAYER_TIME.toInstant());
        }
        return b.build();
    }

    private static final FilePermissions EXECUTABLE = FilePermissions.fromOctalString("755");

    /**
     * 0755 for a file its owner may execute, else 0644: an entrypoint script or a bundled binary keeps
     * its exec bit. A file system without POSIX modes gives every file 0644.
     */
    static FilePermissions permissions(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix == null) return FilePermissions.DEFAULT_FILE_PERMISSIONS;
        return posix.readAttributes().permissions().contains(PosixFilePermission.OWNER_EXECUTE)
                ? EXECUTABLE
                : FilePermissions.DEFAULT_FILE_PERMISSIONS;
    }
}
