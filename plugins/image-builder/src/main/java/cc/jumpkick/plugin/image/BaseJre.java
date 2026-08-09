// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.TarImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * The {@code java} binary out of a base image, without a container runtime.
 *
 * <p>An AOT cache is only valid for the exact JVM build that produced it — the archive records the
 * OS, architecture, build number and the compiler HotSpot was built with. Training therefore has to
 * use the image's own JVM. Running the image is one way; pulling the image's layers and using the
 * binary directly is the other, and it needs no daemon, which is the whole reason jk builds images
 * with Jib.
 *
 * <p>Only usable when the host can execute that binary: a linux-amd64 JRE runs on a linux-amd64
 * host and nowhere else. {@link AotCacheTrainer} falls back to the container path when it cannot.
 */
final class BaseJre {

    private BaseJre() {}

    /** True when this host can execute a Linux binary of the image's architecture. */
    static boolean hostCanExecute(List<String> platforms) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) return false;
        String hostArch = normalizeArch(System.getProperty("os.arch", ""));
        // The default when nothing is declared is linux/amd64, matching ImageConfig.
        String target = platforms == null || platforms.isEmpty() ? "linux/amd64" : platforms.getFirst();
        if (platforms != null && platforms.size() > 1) return false; // multi-arch: no single JVM to train with
        String[] parts = target.split("/");
        if (parts.length < 2 || !parts[0].equals("linux")) return false;
        return normalizeArch(parts[1]).equals(hostArch);
    }

    private static String normalizeArch(String arch) {
        String a = arch.toLowerCase(Locale.ROOT);
        if (a.equals("x86_64") || a.equals("amd64")) return "amd64";
        if (a.equals("aarch64") || a.equals("arm64")) return "arm64";
        return a;
    }

    /**
     * Materialize {@code base}'s JRE under {@code cacheRoot} and return its {@code java}, or null
     * when the image carries none. Cached by image reference — the extraction costs one pull and an
     * untar, and neither is worth repeating per build.
     */
    static Path javaBinary(String base, Path cacheRoot) throws IOException, InterruptedException {
        Path root = cacheRoot.resolve("base-jre").resolve(digest(base));
        Path marker = root.resolve(".extracted");
        if (!Files.isRegularFile(marker)) {
            extract(base, root);
            Files.writeString(marker, base);
        }
        return findJava(root);
    }

    /**
     * Write the base image to a tarball with Jib — the same pull Jib performs for the real build,
     * through the public API and the same layer cache — then unpack its layers.
     */
    private static void extract(String base, Path root) throws IOException, InterruptedException {
        Path tar = root.resolveSibling(root.getFileName() + ".tar");
        Files.createDirectories(root);
        try {
            Jib.from(RegistryImage.named(base))
                    .setEntrypoint("/bin/sh")
                    .containerize(Containerizer.to(TarImage.at(tar).named("jk-base-jre")));
        } catch (InvalidImageReferenceException | RegistryException e) {
            throw new IOException("cannot read base image " + base + ": " + e.getMessage(), e);
        } catch (com.google.cloud.tools.jib.api.CacheDirectoryCreationException
                | java.util.concurrent.ExecutionException e) {
            throw new IOException("cannot extract base image " + base + ": " + e.getMessage(), e);
        }
        // An image tarball is a tar of layer tarballs plus metadata; unpack every layer over the
        // same root, in order, so later layers win the way the runtime filesystem would resolve them.
        Path layers = Files.createTempDirectory(root, "layers-");
        unpack(tar, layers, false);
        try (var walk = Files.walk(layers)) {
            for (Path candidate : walk.filter(Files::isRegularFile).sorted().toList()) {
                if (isGzip(candidate) || candidate.getFileName().toString().endsWith(".tar")) {
                    unpack(candidate, root, true);
                }
            }
        }
        Files.deleteIfExists(tar);
    }

    private static boolean isGzip(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(2);
            return magic.length == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b;
        }
    }

    /** Unpack a (possibly gzipped) tar, skipping anything that would escape {@code dest}. */
    private static void unpack(Path archive, Path dest, boolean tolerate) throws IOException {
        try (InputStream raw = Files.newInputStream(archive);
                InputStream in = isGzip(archive) ? new GZIPInputStream(raw) : raw;
                TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) continue; // path traversal in an untrusted archive
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                // A symlink entry carries no content: writing it as a file leaves a 0-byte stub
                // that execs successfully and does nothing, which is worse than not having it.
                if (entry.isSymbolicLink() || entry.isLink() || !entry.isFile()) continue;
                Files.createDirectories(target.getParent());
                try {
                    Files.copy(tar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if ((entry.getMode() & 0100) != 0) target.toFile().setExecutable(true, false);
                } catch (IOException e) {
                    if (!tolerate) throw e;
                }
            }
        }
    }

    /**
     * A {@code bin/java} in the extracted tree that actually runs.
     *
     * <p>Images carry several: {@code /usr/bin/java} is usually a symlink, which unpacks to
     * nothing, and an empty file execs with status 0 and no output — a failure that looks like
     * success. So candidates are ordered by depth (the real JVM lives under
     * {@code lib/jvm/<dist>/bin}) and each is proven with {@code -version} before it is returned.
     */
    private static Path findJava(Path root) throws IOException, InterruptedException {
        List<Path> candidates;
        try (var walk = Files.walk(root)) {
            candidates = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("java"))
                    .filter(p -> p.getParent() != null
                            && p.getParent().getFileName().toString().equals("bin"))
                    .filter(p -> {
                        try {
                            return Files.size(p) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted(java.util.Comparator.comparingInt((Path p) -> p.getNameCount())
                            .reversed())
                    .toList();
        }
        for (Path candidate : candidates) {
            candidate.toFile().setExecutable(true, false);
            if (runsVersion(candidate)) return candidate;
        }
        return null;
    }

    /** True when {@code java -version} exits 0 and says something. */
    private static boolean runsVersion(Path javaBin) throws InterruptedException {
        try {
            Process p = new ProcessBuilder(javaBin.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isBlank();
        } catch (IOException e) {
            return false;
        }
    }

    private static String digest(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
