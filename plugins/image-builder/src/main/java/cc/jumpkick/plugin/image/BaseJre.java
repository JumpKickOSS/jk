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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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

    /** How long a mutable-tag extraction is trusted before the registry is re-asked. */
    private static final long REVALIDATE_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * Materialize {@code base}'s JRE under {@code cacheRoot} and return its {@code java}, or null
     * when the image carries none. Cached by image reference, validated by the <em>resolved</em>
     * digest: a republished tag must not keep training (and verifying!) with the previous JVM —
     * the shipped image would carry an AOT cache the runtime silently rejects (JK-1757).
     * Digest-pinned references never re-validate; mutable tags re-resolve after
     * {@link #REVALIDATE_MILLIS} (Jib's layer cache makes an unchanged re-pull cheap).
     */
    static Path javaBinary(String base, Path cacheRoot) throws IOException, InterruptedException {
        Path root = cacheRoot.resolve("base-jre").resolve(digest(base));
        Path marker = root.resolve(".extracted");
        boolean pinned = base.contains("@sha256:");
        if (Files.isRegularFile(marker)) {
            long age = System.currentTimeMillis()
                    - Files.getLastModifiedTime(marker).toMillis();
            if (pinned || age < REVALIDATE_MILLIS) return findJava(root);
        }
        extractIfChanged(base, root, marker);
        return findJava(root);
    }

    /**
     * Write the base image to a tarball with Jib — the same pull Jib performs for the real build,
     * through the public API and the same layer cache — then unpack its layers into a fresh tree
     * and swap it in. Skips the unpack when the registry still serves the digest already
     * extracted.
     */
    private static void extractIfChanged(String base, Path root, Path marker) throws IOException {
        Files.createDirectories(root.getParent());
        Path tar = root.resolveSibling(root.getFileName() + ".tar");
        com.google.cloud.tools.jib.api.JibContainer pulled;
        try {
            pulled = Jib.from(RegistryImage.named(base))
                    .setEntrypoint("/bin/sh")
                    .containerize(Containerizer.to(TarImage.at(tar).named("jk-base-jre")));
        } catch (InvalidImageReferenceException | RegistryException e) {
            throw new IOException("cannot read base image " + base + ": " + e.getMessage(), e);
        } catch (com.google.cloud.tools.jib.api.CacheDirectoryCreationException | ExecutionException e) {
            throw new IOException("cannot extract base image " + base + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("base image pull interrupted", e);
        }
        String resolved = pulled.getDigest().toString();
        String previous = null;
        if (Files.isRegularFile(marker)) {
            String[] lines = Files.readString(marker).split("\n");
            if (lines.length > 1) previous = lines[1].trim();
        }
        if (resolved.equals(previous)) {
            // Same bytes — refresh the trust window and keep the tree.
            Files.setLastModifiedTime(marker, FileTime.fromMillis(System.currentTimeMillis()));
            Files.deleteIfExists(tar);
            return;
        }
        Path fresh = root.resolveSibling(root.getFileName() + ".fresh");
        deleteRecursively(fresh);
        Files.createDirectories(fresh);
        unpackImage(tar, fresh);
        Files.deleteIfExists(tar);
        deleteRecursively(root);
        Files.move(fresh, root);
        Files.writeString(root.resolve(".extracted"), base + "\n" + resolved + "\n");
    }

    /**
     * Unpack a docker-archive tarball: layers applied in <em>manifest order</em> (not file-name
     * order — digest-lexicographic ordering has no relation to layer order) with OCI whiteouts
     * honored, so "later layer wins" matches the runtime filesystem.
     */
    private static void unpackImage(Path tar, Path root) throws IOException {
        Path layers = Files.createTempDirectory(root, "layers-");
        unpack(tar, layers, false);
        List<Path> ordered = manifestLayerOrder(layers);
        if (ordered.isEmpty()) {
            // No manifest.json (not a docker archive?) — fall back to name order, old behavior.
            try (var walk = Files.walk(layers)) {
                ordered = walk.filter(Files::isRegularFile)
                        .filter(c -> {
                            try {
                                return isGzip(c) || c.getFileName().toString().endsWith(".tar");
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .sorted()
                        .toList();
            }
        }
        for (Path layer : ordered) {
            unpack(layer, root, true);
        }
        deleteRecursively(layers);
    }

    /**
     * The {@code Layers} list from the archive's {@code manifest.json}, resolved to files. The
     * docker-archive manifest is a stable one-object format; the plugin carries no JSON
     * dependency, so the list is pulled with a scoped regex.
     */
    private static List<Path> manifestLayerOrder(Path layers) throws IOException {
        Path manifest = layers.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) return List.of();
        String body = Files.readString(manifest);
        var m = Pattern.compile("\"Layers\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(body);
        if (!m.find()) return List.of();
        List<Path> out = new ArrayList<>();
        var entry = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
        while (entry.find()) {
            Path layer = layers.resolve(entry.group(1)).normalize();
            if (layer.startsWith(layers) && Files.isRegularFile(layer)) out.add(layer);
        }
        return out;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
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
                String name =
                        target.getFileName() == null ? "" : target.getFileName().toString();
                // OCI whiteouts: `.wh..wh..opq` clears the directory it sits in; `.wh.<x>`
                // deletes <x> from lower layers. Ignoring them resurrects files the image
                // deliberately removed.
                if (name.equals(".wh..wh..opq")) {
                    Path dir = target.getParent();
                    if (dir != null && Files.isDirectory(dir) && dir.startsWith(dest)) {
                        try (var children = Files.list(dir)) {
                            for (Path child : children.toList()) deleteRecursively(child);
                        }
                    }
                    continue;
                }
                if (name.startsWith(".wh.")) {
                    Path victim = target.resolveSibling(name.substring(".wh.".length()));
                    if (victim.startsWith(dest)) deleteRecursively(victim);
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                // A symlink entry carries no content: writing it as a file leaves a 0-byte stub
                // that execs successfully and does nothing, which is worse than not having it.
                if (entry.isSymbolicLink() || entry.isLink() || !entry.isFile()) continue;
                Files.createDirectories(target.getParent());
                try {
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
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
                    .sorted(Comparator.comparingInt((Path p) -> p.getNameCount())
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
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isBlank();
        } catch (IOException e) {
            return false;
        }
    }

    private static String digest(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
