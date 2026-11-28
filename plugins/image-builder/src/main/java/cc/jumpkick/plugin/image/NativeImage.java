// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jsonl.MiniJson;
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
import com.google.cloud.tools.jib.api.buildplan.ImageFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * An image of a native executable: one layer holding it at mode 0755, and the executable as the
 * entrypoint. One executable per platform; with several, the platforms' images go under one OCI
 * image index, in the registry or in an OCI layout tarball.
 */
public final class NativeImage {

    private NativeImage() {}

    /**
     * What to build. {@code executables} maps each {@code os/arch} platform to its executable, in
     * the order the index lists them; {@code path} is where the executable lies in the image.
     */
    public record Plan(
            ImageConfig config, String artifact, String version, Map<String, Path> executables, String path) {
        public Plan {
            Objects.requireNonNull(config, "config");
            if (executables.isEmpty()) throw new IllegalArgumentException("a native image needs an executable");
            executables = Collections.unmodifiableMap(new LinkedHashMap<>(executables));
        }

        String reference() {
            return config.targetReference(artifact, version);
        }
    }

    /** The executable's mode in the image, whatever the file's mode on the build host. */
    static final FilePermissions EXECUTABLE = FilePermissions.fromOctalString("755");

    /** The OCI layout annotation naming an image by its tag. */
    static final String REF_NAME = "org.opencontainers.image.ref.name";

    /** The layer holding {@code executable} at the image path {@code path}, mode 0755. */
    static FileEntriesLayer layer(Path executable, String path) {
        return FileEntriesLayer.builder()
                .setName("executable")
                .addEntry(executable, AbsoluteUnixPath.get(path), EXECUTABLE, AotCacheTrainer.LAYER_TIME.toInstant())
                .build();
    }

    /** Write a local tarball: a single image for one platform, else an OCI layout holding the index. */
    public static ImageBuilder.Result writeToTarball(Plan plan, Path tarball, RegistryAuth auth)
            throws IOException, InterruptedException {
        try {
            if (plan.executables().size() == 1) {
                String platform = plan.executables().keySet().iterator().next();
                return result(
                        plan,
                        build(
                                plan,
                                platform,
                                Containerizer.to(TarImage.at(tarball).named(plan.reference())),
                                auth,
                                false));
            }
            // Beside the tarball, not in the system temp: the parts are as large as the image.
            Path dir = Files.createDirectories(tarball.toAbsolutePath().getParent());
            Path scratch = Files.createTempDirectory(dir, ".jk-native-image-");
            try {
                Map<String, Path> parts = new LinkedHashMap<>();
                for (String platform : plan.executables().keySet()) {
                    Path part = scratch.resolve(platform.replace('/', '-') + ".tar");
                    build(plan, platform, Containerizer.to(TarImage.at(part).named(plan.reference())), auth, true);
                    parts.put(platform, part);
                }
                String digest = mergeLayouts(parts, plan.config().tagOr(plan.version()), tarball);
                return new ImageBuilder.Result(plan.reference(), digest);
            } finally {
                PathUtil.deleteRecursively(scratch);
            }
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /** Load the one platform's image into the local daemon; a daemon holds one platform. */
    public static ImageBuilder.Result loadToLocalDaemon(Plan plan, @Nullable Path dockerExecutable, RegistryAuth auth)
            throws IOException, InterruptedException {
        if (plan.executables().size() != 1) {
            throw new IOException("a daemon holds one platform; the spec names "
                    + String.join(", ", plan.executables().keySet()));
        }
        try {
            DockerDaemonImage target = DockerDaemonImage.named(plan.reference());
            if (dockerExecutable != null) target = target.setDockerExecutable(dockerExecutable);
            String platform = plan.executables().keySet().iterator().next();
            return result(plan, build(plan, platform, Containerizer.to(target), auth, false));
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /**
     * Push to the registry the config names: one image, or each platform's image and then the index
     * over them at the tag. The result's digest is the index's when there is one.
     */
    public static ImageBuilder.Result pushToRegistry(Plan plan, RegistryAuth auth)
            throws IOException, InterruptedException {
        try {
            if (plan.executables().size() == 1) {
                String platform = plan.executables().keySet().iterator().next();
                return result(
                        plan, build(plan, platform, Containerizer.to(auth.target(plan.reference())), auth, false));
            }
            Map<String, String> pushed = new LinkedHashMap<>();
            for (String platform : plan.executables().keySet()) {
                JibContainer one = build(plan, platform, Containerizer.to(auth.target(plan.reference())), auth, true);
                pushed.put(platform, one.getDigest().toString());
            }
            return new ImageBuilder.Result(plan.reference(), Referrers.putIndex(plan.reference(), pushed, auth));
        } catch (InvalidImageReferenceException e) {
            throw new IOException("invalid target image reference: " + e.getMessage(), e);
        }
    }

    /** {@code config} built for {@code platform} alone. */
    static ImageConfig on(ImageConfig config, String platform) {
        return new ImageConfig(
                config.base(),
                config.name(),
                config.user(),
                config.ports(),
                config.env(),
                Map.of(),
                config.labels(),
                config.registry(),
                config.tag(),
                List.of(platform),
                null,
                config.dockerExecutable(),
                null,
                false,
                config.referrers(),
                config.sigstore());
    }

    /** The image of {@code platform}'s executable; {@code oci} writes OCI media types, as an index needs. */
    private static JibContainer build(
            Plan plan, String platform, Containerizer containerizer, RegistryAuth auth, boolean oci)
            throws IOException, InterruptedException, InvalidImageReferenceException {
        ImageConfig config = on(plan.config(), platform);
        Path executable = Objects.requireNonNull(plan.executables().get(platform), platform);
        JibContainerBuilder builder = Jib.from(auth.base(ImageBuilder.baseOf(config)))
                .addFileEntriesLayer(layer(executable, plan.path()))
                .setEntrypoint(List.of(plan.path()));
        String dir = plan.path().substring(0, Math.max(1, plan.path().lastIndexOf('/')));
        builder = builder.setWorkingDirectory(AbsoluteUnixPath.get(dir));
        if (oci) builder = builder.setFormat(ImageFormat.OCI);
        return ImageBuilder.finish(builder, config, containerizer, auth);
    }

    private static ImageBuilder.Result result(Plan plan, JibContainer container) {
        return new ImageBuilder.Result(plan.reference(), container.getDigest().toString());
    }

    /**
     * One OCI image layout tarball at {@code out} from the single-image layouts {@code parts} (one
     * per platform): every blob once, an image index over the platforms' manifests, and an {@code
     * index.json} naming that index under {@code tag}. Returns the index's digest.
     */
    static String mergeLayouts(Map<String, Path> parts, String tag, Path out) throws IOException {
        Map<String, byte[]> blobs = new TreeMap<>();
        Map<String, Map<String, Object>> manifests = new LinkedHashMap<>();
        for (Map.Entry<String, Path> part : parts.entrySet()) {
            Map<String, byte[]> files = untar(part.getValue());
            byte[] index = files.get("index.json");
            if (index == null) throw new IOException("image layout without index.json: " + part.getValue());
            List<?> listed =
                    MiniJson.list(MiniJson.parseRelaxed(new String(index, StandardCharsets.UTF_8)), "manifests");
            if (listed.size() != 1) throw new IOException("image layout does not hold one image: " + part.getValue());
            String digest = Objects.requireNonNull(MiniJson.str(listed.getFirst(), "digest"), "manifest digest");
            byte[] manifest = files.get("blobs/" + digest.replace(':', '/'));
            if (manifest == null) throw new IOException("image layout lacks its manifest: " + part.getValue());
            manifests.put(part.getKey(), Referrers.platformDescriptor(manifest, digest, part.getKey()));
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                if (file.getKey().startsWith("blobs/")) blobs.put(file.getKey(), file.getValue());
            }
        }
        byte[] index = Referrers.index(new ArrayList<>(manifests.values()));
        String indexDigest = "sha256:" + Hashing.sha256Hex(index);
        blobs.put("blobs/" + indexDigest.replace(':', '/'), index);
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("mediaType", Referrers.INDEX_TYPE);
        descriptor.put("digest", indexDigest);
        descriptor.put("size", index.length);
        descriptor.put("annotations", Map.of(REF_NAME, tag));
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("schemaVersion", 2);
        top.put("mediaType", Referrers.INDEX_TYPE);
        top.put("manifests", List.of(descriptor));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(os)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            put(
                    tar,
                    "oci-layout",
                    MiniJson.write(Map.of("imageLayoutVersion", "1.0.0")).getBytes(StandardCharsets.UTF_8));
            put(tar, "index.json", MiniJson.write(top).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> blob : blobs.entrySet()) put(tar, blob.getKey(), blob.getValue());
        }
        return indexDigest;
    }

    private static void put(TarArchiveOutputStream tar, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        entry.setModTime(AotCacheTrainer.LAYER_TIME.toInstant().toEpochMilli());
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    /** A tarball's regular files by name, a leading {@code ./} stripped. */
    static Map<String, byte[]> untar(Path tarball) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(tarball);
                TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isFile()) continue;
                String name = entry.getName().startsWith("./") ? entry.getName().substring(2) : entry.getName();
                files.put(name, tar.readAllBytes());
            }
        }
        return files;
    }
}
