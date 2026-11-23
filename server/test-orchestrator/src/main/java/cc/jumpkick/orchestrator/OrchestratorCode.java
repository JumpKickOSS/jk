// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.BuildIdentity;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The identity of the code that judges a suite: the compiled classes of this package, read from
 * wherever they were loaded. A run-tests stamp keys by it, so a change to how a suite's outcome is
 * read runs every suite again, and an engine change outside this package replays them.
 *
 * <p>In the engine's jar the classes sit beside the rest of the engine, so the package is the
 * boundary: every {@code .class} entry under {@code cc/jumpkick/orchestrator/}, by name, CRC-32 and
 * size as the archive's central directory records them, which answers without inflating an entry.
 * From a classes directory each file is read by content. A location that cannot be read keys by the
 * whole engine ({@link BuildIdentity#keyId}), which runs suites too often and never too seldom.
 */
public final class OrchestratorCode {

    /** The package every class of this module lives in, as an archive path. */
    static final String PACKAGE = "cc/jumpkick/orchestrator/";

    private static volatile @Nullable String override;

    private OrchestratorCode() {}

    /** The 12-hex digest of the classes, read once per process. */
    public static String digest() {
        String o = override;
        return o != null ? o : Read.DIGEST;
    }

    /** Pretend the classes are the ones {@code digest} names; {@code null} returns to the classes read. */
    public static void overrideDigestForTests(@Nullable String digest) {
        override = digest;
    }

    /** Read on first use: the classes do not change under their process. */
    private static final class Read {
        static final String DIGEST = read();
    }

    private static String read() {
        try {
            Path location = location();
            return location == null ? whole() : of(location);
        } catch (IOException | RuntimeException e) {
            return whole();
        }
    }

    /** Where the classes were loaded from, a jar or a classes directory; {@code null} when that is not said. */
    static @Nullable Path location() {
        try {
            CodeSource source = OrchestratorCode.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static String whole() {
        return "engine-" + BuildIdentity.keyId();
    }

    /** The digest of the package's classes under {@code location}, a jar or a classes directory. */
    static String of(Path location) throws IOException {
        SortedMap<String, String> classes = new TreeMap<>();
        if (Files.isDirectory(location)) {
            Path dir = location.resolve(PACKAGE);
            if (Files.isDirectory(dir)) {
                PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                    if (!file.toString().endsWith(".class")) return;
                    String name = location.relativize(file).toString().replace('\\', '/');
                    classes.put(name, Hashing.sha256Hex(file));
                });
            }
        } else {
            try (ZipFile zip = new ZipFile(location.toFile())) {
                for (ZipEntry e : Collections.list(zip.entries())) {
                    String name = e.getName();
                    if (!name.startsWith(PACKAGE) || !name.endsWith(".class")) continue;
                    classes.put(name, Long.toHexString(e.getCrc()) + " " + e.getSize());
                }
            }
        }
        if (classes.isEmpty()) throw new IOException("no class under " + PACKAGE + " in " + location);
        MessageDigest md = Hashing.newSha256();
        classes.forEach((name, identity) -> md.update((name + " " + identity + "\n").getBytes(StandardCharsets.UTF_8)));
        return Hashing.hex(md.digest()).substring(0, 12);
    }
}
