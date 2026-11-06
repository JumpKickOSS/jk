// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Local-path dependency (compile/package only, not a workspace member). {@code rawPath} is as
 * written, relative to the directory of the {@code jk.toml} that declares it; coords come from the
 * target at materialization.
 */
public record PathSource(String rawPath) {

    public PathSource {
        Objects.requireNonNull(rawPath, "rawPath");
        if (rawPath.isBlank()) {
            throw new IllegalArgumentException("path dependency rawPath must not be blank");
        }
    }

    /** The target directory, {@code rawPath} resolved from {@code declaringDir}. */
    public Path dir(Path declaringDir) {
        return declaringDir.resolve(rawPath).normalize();
    }
}
