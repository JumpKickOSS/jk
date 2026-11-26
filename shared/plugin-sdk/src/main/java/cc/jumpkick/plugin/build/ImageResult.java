// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of an {@link ImageExtension#image} run — enough for the worker entry to report it: a
 * pushed/loaded image {@code reference}, or a written {@code tarball} path. Exactly one is set.
 * {@code digest} is the manifest digest a push left in the registry, {@code null} for any other.
 */
public record ImageResult(
        @Nullable String reference,
        @Nullable Path tarball,
        boolean daemon,
        @Nullable String digest) {

    /** An image pushed to a registry under {@code digest}. */
    public static ImageResult pushed(String reference, String digest) {
        return new ImageResult(reference, null, false, digest);
    }

    /** An image loaded into the local container daemon. */
    public static ImageResult loaded(String reference) {
        return new ImageResult(reference, null, true, null);
    }

    /** An image written to an OCI tarball. */
    public static ImageResult tarball(Path path) {
        return new ImageResult(null, path, false, null);
    }
}
