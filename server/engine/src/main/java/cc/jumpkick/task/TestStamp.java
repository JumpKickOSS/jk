// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Content-hashed key for incremental test skipping. On green runs the build stores a CAS marker
 * under this key (survives {@code jk clean}); a later matching key skips the runner. Inputs: test
 * sources, main classes, lockfile, runtime classpath by content ({@link ClasspathFingerprint}),
 * and toolchain/runner tokens. {@link #computeKey} returns {@code null} on any I/O failure —
 * callers treat that as uncached and retest.
 */
public final class TestStamp {

    /** Prefix embedded in the key so a future format change invalidates it. */
    private static final String FORMAT_VERSION = "test-stamp-v2";

    private TestStamp() {}

    /**
     * Combined content key for all test inputs, or {@code null} if any required input is
     * unreadable (callers must retest).
     */
    public static String computeKey(
            List<Path> testSources, Path mainClasses, Path lockFile, List<Path> runtimeCp, List<String> extraInputs) {
        try {
            MessageDigest md = Hashing.newSha256();
            feed(md, FORMAT_VERSION);

            // Test sources: path + content hash, sorted for stability.
            List<Path> sortedSources = new ArrayList<>(testSources);
            sortedSources.sort(Comparator.comparing(Path::toString));
            for (Path src : sortedSources) {
                if (!Files.isRegularFile(src)) continue; // generated / deleted
                feed(md, "src:" + src.toAbsolutePath().normalize() + ":" + Hashing.sha256Hex(Files.readAllBytes(src)));
            }

            // The module's own compiled main output — a main-only change busts the
            // stamp even when no test source changed (the tests exercise this code).
            if (mainClasses != null) {
                feed(md, "main:" + ClasspathFingerprint.entry(mainClasses));
            }

            // Lock file: content hash — catches any dep version / JDK change.
            if (Files.isRegularFile(lockFile)) {
                feed(md, "lock:" + Hashing.sha256Hex(Files.readAllBytes(lockFile)));
            }

            // Runtime classpath by CONTENT: a sibling module's change ripples in,
            // and a byte-identical rebuild (new mtime, same bytes) does not.
            feed(md, "cp:" + ClasspathFingerprint.of(runtimeCp));

            // Toolchain / runner / forked-worker identity, sorted for stability.
            if (extraInputs != null) {
                List<String> extras = new ArrayList<>(extraInputs);
                extras.sort(Comparator.naturalOrder());
                for (String e : extras) feed(md, "x:" + e);
            }

            return Hashing.hex(md.digest());
        } catch (IOException e) {
            return null; // fail open — retest
        }
    }

    // -----------------------------------------------------------------------
    // Helpers

    private static void feed(MessageDigest md, String value) {
        byte[] bytes = (value + "\n").getBytes(StandardCharsets.UTF_8);
        md.update(bytes);
    }
}
