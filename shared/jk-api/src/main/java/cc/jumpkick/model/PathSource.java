// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * Local-path dependency (compile/package only, not a workspace member). {@code rawPath} is as
 * written; coords come from the target at materialization.
 */
public record PathSource(String rawPath) {

    public PathSource {
        Objects.requireNonNull(rawPath, "rawPath");
        if (rawPath.isBlank()) {
            throw new IllegalArgumentException("path dependency rawPath must not be blank");
        }
    }
}
