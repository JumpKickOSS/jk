// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.MinimalTar;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Download/extract a {@link ToolDistribution} under {@code $JK_CACHE_DIR/tools/<slug>/<version>/}
 * (zip/tar.gz; optional SHA-256; fail cleans partial install).
 */
public final class ToolInstaller {

    private final Http http;
    private final ToolRegistry registry;

    public ToolInstaller(Http http, ToolRegistry registry) {
        this.http = Objects.requireNonNull(http, "http");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public InstalledTool install(ToolDistribution dist) throws IOException, InterruptedException {
        Path target = registry.installDir(dist.tool(), dist.version());
        if (Files.isDirectory(target)) {
            return new InstalledTool(dist.tool(), dist.version(), target);
        }
        Files.createDirectories(target.getParent());

        Path archive = Files.createTempFile("jk-tool-", "-" + dist.archiveType());
        try {
            // Streamed to disk, never held whole. The engine runs under a memory cap (248 MiB by
            // default) and the Kotlin compiler distribution alone is 83 MiB, so buffering the body
            // in a byte[] made every first `.kts` build-logic run, and every Kotlin/Maven/Gradle
            // provision, an OutOfMemoryError on a stock engine.
            HttpResponse<InputStream> response = http.getStream(dist.downloadUri());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException(dist.tool().slug()
                            + " download "
                            + dist.downloadUri()
                            + " returned "
                            + response.statusCode());
                }
                Files.copy(body, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            if (dist.sha256() != null && !dist.sha256().isEmpty()) {
                String actual = Hashing.sha256Hex(archive);
                if (!actual.equalsIgnoreCase(dist.sha256())) {
                    throw new IOException("sha256 mismatch for "
                            + dist.downloadUri()
                            + " — expected "
                            + dist.sha256()
                            + ", got "
                            + actual);
                }
            }
            SessionContext.current().io().remoteDown(archive);

            // Stage NEXT TO the target (same filesystem): the install is then one atomic
            // rename, so a crash or a racing provision never leaves a partial tree at target
            // — and a cross-filesystem /tmp (tmpfs) can't fail the per-directory moves
            // (Files.move of a non-empty dir across filesystems always throws).
            Path stagingDir = Files.createTempDirectory(target.getParent(), "jk-tool-stage-");
            try {
                extract(archive, stagingDir, dist.archiveType());
                Path effectiveRoot = flattenedRoot(stagingDir);
                try {
                    Files.move(effectiveRoot, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException moveFailed) {
                    // A concurrent provision published first — its tree is complete; use it.
                    if (!Files.isDirectory(target)) throw moveFailed;
                }
                ensureBinaryExecutable(target, dist.tool());
            } finally {
                deleteRecursively(stagingDir); // leftover shell when the root was nested, or on failure
            }
        } finally {
            Files.deleteIfExists(archive);
        }
        return new InstalledTool(dist.tool(), dist.version(), target);
    }

    private static void extract(Path archive, Path destDir, String archiveType) throws IOException {
        Files.createDirectories(destDir);
        switch (archiveType) {
            case "zip" -> unzip(archive, destDir);
            case "tar.gz" -> extractTarGz(archive, destDir);
            default -> throw new IOException("unsupported archive type: " + archiveType);
        }
    }

    private static void unzip(Path archive, Path destDir) throws IOException {
        try (InputStream in = Files.newInputStream(archive);
                ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) {
                    throw new IOException("zip entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.copy(zis, out);
                }
            }
        }
    }

    private static void extractTarGz(Path archive, Path destDir) throws IOException {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(archive));
                GZIPInputStream gz = new GZIPInputStream(fis)) {
            MinimalTar.stream(gz, (name, linkName, mode, isDir, isLink, data, size) -> {
                Path out = destDir.resolve(name).normalize();
                if (!out.startsWith(destDir)) {
                    throw new IOException("tar entry escapes destination: " + name);
                }
                if (isDir) {
                    Files.createDirectories(out);
                } else if (isLink) {
                    MinimalTar.createSymlinkInside(destDir, out, linkName);
                } else {
                    MinimalTar.requireParentInside(destDir, out);
                    Files.copy(data, out);
                    MinimalTar.applyMode(out, mode);
                }
            });
        }
    }

    /**
     * Zip archives carry no Unix mode bits, so {@code bin/mvn} or {@code bin/gradle} arrives without
     * the +x bit. Set it explicitly so {@code ProcessBuilder} can exec the launcher.
     */
    private static void ensureBinaryExecutable(Path home, BuildTool tool) {
        Path bin = home.resolve("bin").resolve(tool.binaryName());
        if (!Files.exists(bin)) return;
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(bin));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(bin, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX filesystem — .cmd/.bat needs no +x bit.
        }
    }

    /**
     * Maven and Gradle archives unpack into a single top-level directory (e.g. {@code
     * apache-maven-3.9.9/}, {@code gradle-9.5.1/}). Strip it so {@code home/bin/} is reachable.
     */
    private static Path flattenedRoot(Path stagingDir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var stream = Files.list(stagingDir)) {
            stream.forEach(children::add);
        }
        if (children.size() == 1 && Files.isDirectory(children.getFirst())) {
            return children.getFirst();
        }
        return stagingDir;
    }

    private static void deleteRecursively(Path root) {
        PathUtil.deleteRecursively(root);
    }
}
