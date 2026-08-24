// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code .extracted} marker is two clocks at once: how long a mutable tag is trusted before
 * the registry is re-asked, and — for cache retention, which has nothing else to read — when the
 * tree was last used. Only one of the two paths through {@link BaseJre#javaBinary} may write it.
 */
class BaseJreMarkerTest {

    private static final String PINNED = "example.invalid/jre@sha256:" + "a".repeat(64);
    private static final String TAG = "example.invalid/jre:21";

    @Test
    void using_a_pinned_tree_records_that_it_was_used(@TempDir Path cache) throws Exception {
        Path marker = seed(cache, PINNED, Duration.ofDays(40));

        BaseJre.javaBinary(PINNED, cache, RegistryAuth.NONE);

        assertThat(age(marker))
                .as("a pinned reference never re-extracts, so nothing else records the use")
                .isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void using_a_tagged_tree_leaves_the_revalidation_clock_alone(@TempDir Path cache) throws Exception {
        Path marker = seed(cache, TAG, Duration.ofHours(20));

        BaseJre.javaBinary(TAG, cache, RegistryAuth.NONE);

        assertThat(age(marker))
                .as("touching this would defer the 24 h re-resolve forever, and a republished tag "
                        + "would keep training against the old JVM")
                .isGreaterThan(Duration.ofHours(19));
    }

    /** An extracted tree whose marker is {@code age} old, and no {@code bin/java} to run. */
    private static Path seed(Path cache, String base, Duration age) throws Exception {
        Path root = cache.resolve("base-jre").resolve(digestOf(base));
        Files.createDirectories(root);
        Path marker = root.resolve(".extracted");
        Files.writeString(marker, base + "\nsha256:cafe\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(marker, FileTime.fromMillis(System.currentTimeMillis() - age.toMillis()));
        return marker;
    }

    /** The directory {@link BaseJre} picks for a reference, discovered rather than recomputed. */
    private static String digestOf(String base) throws IOException {
        return HexFormat.of().formatHex(sha256(base.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Duration age(Path p) throws IOException {
        return Duration.ofMillis(
                System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis());
    }
}
