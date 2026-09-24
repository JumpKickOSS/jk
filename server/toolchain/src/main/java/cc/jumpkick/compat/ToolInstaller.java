// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.MinimalTar;
import cc.jumpkick.util.JkOwnership;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Download/extract a {@link ToolDistribution} under {@code $JK_STORE_DIR/tools/<slug>/<version>/}
 * (zip/tar.gz; fail cleans partial install).
 *
 * <p>Every archive is verified before it is unpacked, against the first of these that exists: the
 * distribution's own pin; the digest a run accepted for it earlier ({@link
 * ToolRegistry#acceptedDigest}); the {@link PublishedChecksum} sidecars its publisher puts beside
 * it, strongest first. The sidecars are fetched before the archive, so a distribution that cannot
 * be verified is refused before the archive is downloaded at all. An archive with none of the
 * three is installed only when the run accepts it by name ({@link ToolRegistry#ACCEPT_FLAG}); its SHA-256 is
 * then recorded so the next download of that version is verified, silently, against it. What
 * arrives here is executed — the Kotlin compiler on every {@code .kt} build — so TLS alone is not
 * enough.
 *
 * <p>A {@code file:} distribution — a wrapper pointing at an offline mirror — is copied from disk
 * and held to the same rule: its pin, or a checksum file beside the archive on that disk.
 */
public final class ToolInstaller {

    /** An install and how its archive was verified, in the words the {@code downloaded} line prints. */
    public record Installed(InstalledTool tool, String verification) {}

    private final Http http;
    private final ToolRegistry registry;

    public ToolInstaller(Http http, ToolRegistry registry) {
        this.http = Objects.requireNonNull(http, "http");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public InstalledTool install(ToolDistribution dist) throws IOException, InterruptedException {
        return install(dist, false).tool();
    }

    /**
     * Install {@code dist}; {@code acceptUnverified} lets an archive with no pin, no accepted
     * digest and no published checksum through, recording its SHA-256 for the next time.
     */
    public Installed install(ToolDistribution dist, boolean acceptUnverified) throws IOException, InterruptedException {
        Path target = registry.installDir(dist.tool(), dist.version());
        if (Files.isDirectory(target)) {
            return new Installed(new InstalledTool(dist.tool(), dist.version(), target), "installed earlier");
        }
        Files.createDirectories(target.getParent());

        ExpectedDigest expected = expectedDigest(dist, acceptUnverified);
        Path archive = Files.createTempFile("jk-tool-", "-" + dist.archiveType());
        try {
            fetch(dist, archive);
            String actual = Hashing.fileHex(expected.algorithm(), archive);
            if (expected.hex() == null) {
                // Accepted by name: the archive's own digest becomes the record later downloads
                // are held to. Written before the unpack, so a crash mid-extract still leaves the
                // acceptance the retry needs.
                Path record = registry.acceptedDigest(dist.tool(), dist.version());
                Files.writeString(record, actual + "  " + archiveName(dist) + "\n", StandardCharsets.UTF_8);
            } else if (!actual.equalsIgnoreCase(expected.hex())) {
                throw new IOException(expected.label()
                        + " mismatch for "
                        + dist.downloadUri()
                        + " — expected "
                        + expected.hex()
                        + " ("
                        + expected.source()
                        + "), got "
                        + actual);
            }

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
                // Claim the tree as ours, so a later purge of a broken entry is allowed to
                // recurse into it (JkOwnership). Written after the atomic rename, so a partial
                // tree is never marked.
                JkOwnership.mark(target);
            } finally {
                deleteRecursively(stagingDir); // leftover shell when the root was nested, or on failure
            }
        } finally {
            Files.deleteIfExists(archive);
        }
        return new Installed(new InstalledTool(dist.tool(), dist.version(), target), expected.source());
    }

    /** The archive's file name, as a checksum record names it. */
    private static String archiveName(ToolDistribution dist) {
        String path = dist.downloadUri().getPath();
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return path == null || slash < 0 ? dist.version() + "." + dist.archiveType() : path.substring(slash + 1);
    }

    /** Copy the distribution's archive to {@code archive}: from disk for a {@code file:} URI, else by download. */
    private void fetch(ToolDistribution dist, Path archive) throws IOException, InterruptedException {
        URI uri = dist.downloadUri();
        if (isFile(uri)) {
            Path source = localFile(uri, dist);
            if (!Files.isRegularFile(source)) {
                throw new IOException(
                        dist.tool().slug() + " distribution " + uri + " is not a file on this machine: " + source);
            }
            Files.copy(source, archive, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // Streamed to disk, never held whole. The engine runs under a memory cap (248 MiB by
        // default) and the Kotlin compiler distribution alone is 83 MiB, so buffering the body
        // in a byte[] made every first `.kts` build-logic run, and every Kotlin/Maven/Gradle
        // provision, an OutOfMemoryError on a stock engine.
        HttpResponse<InputStream> response = http.getStream(uri);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(dist.tool().slug() + " download " + uri + " returned " + response.statusCode());
            }
            Files.copy(body, archive, StandardCopyOption.REPLACE_EXISTING);
        }
        SessionContext.current().io().remoteDown(archive);
    }

    private static boolean isFile(URI uri) {
        return uri.getScheme() != null
                && uri.getScheme().toLowerCase(Locale.ROOT).equals("file");
    }

    /** The path a {@code file:} URI names; refused with the distribution named when it names none. */
    private static Path localFile(URI uri, ToolDistribution dist) throws IOException {
        try {
            return Path.of(uri);
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            throw new IOException(
                    dist.tool().slug() + " distribution " + uri + " is not an absolute file:// path: " + e.getMessage(),
                    e);
        }
    }

    /**
     * What the archive must hash to, and where that expectation came from; {@code hex} is null
     * only for an archive accepted by name, whose digest is recorded rather than checked.
     */
    private record ExpectedDigest(
            String label, String algorithm, @Nullable String hex, String source) {}

    /**
     * The distribution's pin, else the digest accepted for it earlier, else the digest the first
     * published sidecar advertises. Refuses — before any archive bytes move — when none of those
     * exists, naming each sidecar tried and why it did not count (absent, or a body that is not a
     * digest of the expected width: a repository answering a missing file with an HTML page under
     * 200), unless {@code acceptUnverified} lets the archive through.
     */
    private ExpectedDigest expectedDigest(ToolDistribution dist, boolean acceptUnverified)
            throws IOException, InterruptedException {
        String pinned = dist.sha256();
        if (pinned != null && !pinned.isBlank()) {
            return new ExpectedDigest(
                    "sha256", "SHA-256", pinned.trim(), "pinned by the wrapper's distributionSha256Sum");
        }
        Path accepted = registry.acceptedDigest(dist.tool(), dist.version());
        if (Files.isRegularFile(accepted)) {
            String hex = Hashing.checksumFromSidecar(Files.readString(accepted, StandardCharsets.UTF_8), 64)
                    .orElseThrow(() -> new IOException(accepted + " is not a sha256 digest; delete it to accept "
                            + dist.tool().slug() + " " + dist.version() + " again"));
            return new ExpectedDigest("sha256", "SHA-256", hex, "verified against the digest accepted earlier");
        }
        List<String> tried = new ArrayList<>();
        for (PublishedChecksum sidecar : dist.tool().publishedChecksums()) {
            URI sidecarUri = sidecar.beside(dist.downloadUri());
            String body;
            if (isFile(sidecarUri)) {
                Path sidecarFile = localFile(sidecarUri, dist);
                if (!Files.isRegularFile(sidecarFile)) {
                    tried.add(sidecarUri + " is not there");
                    continue;
                }
                body = Files.readString(sidecarFile, StandardCharsets.UTF_8);
            } else {
                HttpResponse<byte[]> response = http.get(sidecarUri);
                if (response.statusCode() != 200) {
                    tried.add(sidecarUri + " returned " + response.statusCode());
                    continue;
                }
                body = new String(response.body(), StandardCharsets.UTF_8);
            }
            Optional<String> hex = Hashing.checksumFromSidecar(body, sidecar.hexLength());
            if (hex.isEmpty()) {
                tried.add(sidecarUri + " is not a " + sidecar.label() + " digest");
                continue;
            }
            return new ExpectedDigest(
                    sidecar.label(),
                    sidecar.algorithm(),
                    hex.get(),
                    "verified against the published " + sidecar.suffix());
        }
        if (acceptUnverified) {
            return new ExpectedDigest(
                    "sha256", "SHA-256", null, "accepted with " + ToolRegistry.ACCEPT_FLAG + ", sha256 recorded");
        }
        throw new IOException(unverifiable(dist, tried));
    }

    private static String unverifiable(ToolDistribution dist, List<String> tried) {
        String suffixes = dist.tool().publishedChecksums().stream()
                .map(PublishedChecksum::suffix)
                .collect(Collectors.joining(" or "));
        return dist.tool().slug()
                + " distribution "
                + dist.downloadUri()
                + " cannot be verified: no "
                + suffixes
                + " checksum is published beside it ("
                + String.join("; ", tried)
                + "). Refusing to install an archive nothing vouches for; pin its SHA-256"
                + " (wrapper distributionSha256Sum), or accept this download once with "
                + ToolRegistry.ACCEPT_FLAG
                + " (or "
                + ToolRegistry.ACCEPT_ENV
                + "=1) — jk records its digest under the tools store and verifies later downloads against it.";
    }

    static void extract(Path archive, Path destDir, String archiveType) throws IOException {
        Files.createDirectories(destDir);
        switch (archiveType) {
            case "zip" -> unzip(archive, destDir);
            case "tar.gz" -> extractTarGz(archive, destDir);
            default -> throw new IOException("unsupported archive type: " + archiveType);
        }
    }

    /**
     * Every entry is judged by where it really lands, as the tar path judges its entries:
     * directories through {@link MinimalTar#createDirectoryInside}, file parents through {@link
     * MinimalTar#requireParentInside} — one judge for one destination.
     */
    private static void unzip(Path archive, Path destDir) throws IOException {
        try (InputStream in = Files.newInputStream(archive);
                ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = destDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    MinimalTar.createDirectoryInside(destDir, out);
                } else {
                    MinimalTar.requireParentInside(destDir, out);
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
                    MinimalTar.createDirectoryInside(destDir, out);
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
     * apache-maven-3.9.9/}, {@code gradle-9.8.0/}). Strip it so {@code home/bin/} is reachable.
     */
    private static Path flattenedRoot(Path stagingDir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var stream = Files.list(stagingDir)) {
            stream.forEach(children::add);
        }
        if (children.size() == 1 && Files.isDirectory(children.getFirst())) {
            Path lifted = children.getFirst();
            // Extraction judged every link against the staging directory; lifted one level, a link
            // that reached up out of this directory would point outside the installed tree.
            MinimalTar.requireSymlinksInside(lifted);
            return lifted;
        }
        return stagingDir;
    }

    private static void deleteRecursively(Path root) {
        PathUtil.deleteRecursively(root);
    }
}
