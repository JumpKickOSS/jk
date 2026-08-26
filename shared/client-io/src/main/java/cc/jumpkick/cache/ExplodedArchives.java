// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Content-addressed exploded views of container archives (e.g. Android AAR → {@code classes.jar},
 * {@code res/}, …). Keyed by SHA-256 like the CAS; plugins receive exploded dirs, not CAS paths.
 */
public final class ExplodedArchives {

    private ExplodedArchives() {}

    /** The exploded dir for the CAS blob {@code sha256Hex}, exploding on first use. */
    public static Path explode(Cas cas, String sha256Hex) throws IOException {
        Path archive = cas.pathFor(sha256Hex);
        if (!Files.isRegularFile(archive)) {
            throw new IOException("archive blob missing from the cache: " + sha256Hex + " — run `jk sync`");
        }
        return explodeAt(cas.root().resolve("exploded").resolve(shard(sha256Hex)), archive);
    }

    /** The exploded dir for an arbitrary archive file (a workspace sibling's AAR), keyed by its content. */
    public static Path explodeFile(Cas cas, Path archive) throws IOException {
        String hex = Hashing.sha256Hex(archive);
        return explodeFile(cas, archive, hex);
    }

    /**
     * As {@link #explodeFile(Cas, Path)} but keyed by an already-known content hash — skips a full
     * re-hash of the archive on every classpath resolution when the caller already holds the verified
     * lock pin (JK-2308).
     */
    public static Path explodeFile(Cas cas, Path archive, String sha256Hex) throws IOException {
        return explodeAt(cas.root().resolve("exploded").resolve(shard(sha256Hex)), archive);
    }

    private static String shard(String hex) {
        return hex.substring(0, 2) + "/" + hex;
    }

    private static Path explodeAt(Path dir, Path archive) throws IOException {
        if (Files.isDirectory(dir)) return dir; // content-addressed: an existing dir is authoritative
        Path staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".exploding-");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path out = staging.resolve(entry.getName()).normalize();
                if (!out.startsWith(staging)) {
                    throw new IOException("archive entry escapes its root (zip-slip): " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try {
            AtomicWrites.publishDir(staging, dir);
        } catch (IOException e) {
            if (!Files.isDirectory(dir)) throw e; // lost a race — the winner's dir serves
            PathUtil.deleteRecursively(staging);
        }
        return dir;
    }
}
