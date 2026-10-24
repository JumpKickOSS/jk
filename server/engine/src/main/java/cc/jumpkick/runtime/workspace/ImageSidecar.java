// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * What jk remembers about an image it built, so the image is kept in one place only.
 *
 * <p>An image already lives somewhere else: as the tarball the user asked for beside the build, or in
 * the local daemon's store (Docker or Podman) once loaded. A second copy in the action cache would be
 * hundreds of megabytes for nothing, so the cache keeps only this record under the image's action
 * key — enough to <em>validate</em> the image where it is, not to rebuild it:
 *
 * <ul>
 *   <li>a tarball: its size, mtime and sha256, so the file in {@code target/} is proven to be the one
 *       jk wrote;
 *   <li>a daemon image: the reference and the image ID the daemon reported when jk loaded it.
 * </ul>
 *
 * A valid image skips the build. One that is gone or changed, or a daemon that cannot answer, is a
 * miss and the image is built again.
 */
final class ImageSidecar {

    static final String TARBALL_SIZE = "tarball-size";
    static final String TARBALL_MTIME = "tarball-mtime";
    static final String TARBALL_SHA256 = "tarball-sha256";
    static final String IMAGE_REF = "image-ref";
    static final String IMAGE_ID = "image-id";

    private ImageSidecar() {}

    /** The record for {@code tarball}, which must exist. */
    static Map<String, String> ofTarball(Path tarball) throws IOException {
        Map<String, String> record = new LinkedHashMap<>();
        record.put(TARBALL_SIZE, Long.toString(Files.size(tarball)));
        record.put(
                TARBALL_MTIME, Long.toString(Files.getLastModifiedTime(tarball).toMillis()));
        record.put(TARBALL_SHA256, Hashing.sha256Hex(tarball));
        return record;
    }

    /** True when {@code tarball} is, by size and mtime, the file {@code record} was made from. */
    static boolean tarballIntact(Map<String, String> record, Path tarball) {
        try {
            return Files.isRegularFile(tarball)
                    && Long.toString(Files.size(tarball)).equals(record.get(TARBALL_SIZE))
                    && Long.toString(Files.getLastModifiedTime(tarball).toMillis())
                            .equals(record.get(TARBALL_MTIME));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** The record for the image the daemon holds as {@code ref} with ID {@code id}. */
    static Map<String, String> ofDaemonImage(String ref, String id) {
        return Map.of(IMAGE_REF, ref, IMAGE_ID, id);
    }

    /** True when the daemon behind {@code exe} still holds the image {@code record} names, under the same ID. */
    static boolean daemonHolds(Map<String, String> record, String exe) {
        String ref = record.get(IMAGE_REF);
        String id = record.get(IMAGE_ID);
        if (ref == null || id == null) return false;
        return daemonImageId(exe, ref).map(id::equals).orElse(false);
    }

    /**
     * The ID {@code exe} (Docker or Podman) gives the image {@code ref}, without the {@code sha256:}
     * prefix Docker adds, or empty when the daemon is not running, is not reachable or holds no such image.
     */
    static Optional<String> daemonImageId(String exe, String ref) {
        try {
            Process p = JobWorkers.start(new ProcessBuilder(exe, "image", "inspect", "--format", "{{.Id}}", ref)
                    .redirectError(ProcessBuilder.Redirect.DISCARD));
            try {
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) return Optional.empty();
                return Optional.of(out.startsWith("sha256:") ? out.substring("sha256:".length()) : out);
            } finally {
                if (p.isAlive()) p.destroyForcibly();
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
