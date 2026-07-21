// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * Tool coordinate for {@code jk tool run|install}: {@code g:a:v[:classifier][@type]} →
 * {@link Pinned}; {@code g:a@selector} or bare {@code g:a} → {@link Floating}. One colon before
 * {@code @} means floating selector; two+ means packaging type on a full GAV.
 */
public sealed interface ToolCoordSpec {

    /** The text the user wrote, for diagnostics. */
    String raw();

    /** {@code group:artifact} module identifier. */
    String module();

    /** An exact {@code g:a:v[:classifier][@type]} — resolve and fetch as-is. */
    record Pinned(Coordinate coordinate, String raw) implements ToolCoordSpec {
        @Override
        public String module() {
            return coordinate.module();
        }
    }

    /** A {@code g:a} plus a selector the resolver must pin against the repo's version list. */
    record Floating(String group, String artifact, VersionSelector selector, String raw) implements ToolCoordSpec {
        @Override
        public String module() {
            return group + ":" + artifact;
        }
    }

    static ToolCoordSpec parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        String trimmed = spec.trim();
        int at = trimmed.indexOf('@');
        String body = at >= 0 ? trimmed.substring(0, at) : trimmed;
        long colons = body.chars().filter(c -> c == ':').count();
        if (colons == 1) {
            int colon = body.indexOf(':');
            String group = body.substring(0, colon);
            String artifact = body.substring(colon + 1);
            if (group.isBlank() || artifact.isBlank()) {
                throw new IllegalArgumentException("tool coordinate must be group:artifact[…], got: " + spec);
            }
            VersionSelector selector = at >= 0
                    ? VersionSelector.parseFloating(trimmed.substring(at + 1))
                    : VersionSelector.parse("latest");
            return new Floating(group, artifact, selector, spec);
        }
        // 2+ colons (or none — Coordinate.parse renders the canonical error).
        return new Pinned(Coordinate.parse(trimmed), spec);
    }
}
