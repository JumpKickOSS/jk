// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * Tool coordinate for {@code jk tool run|install}: {@code g:a:v[:classifier][!type]} →
 * {@link Pinned}; {@code g:a@selector} or bare {@code g:a} → {@link Floating}. {@code @} is always
 * a version selector; {@code !} is packaging type on a full GAV ({@link Coordinate#parse}).
 */
public sealed interface ToolCoordSpec {

    /** The text the user wrote, for diagnostics. */
    String raw();

    /** {@code group:artifact} module identifier. */
    String module();

    /** An exact {@code g:a:v[:classifier][!type]} — resolve and fetch as-is. */
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
        // Strip packaging type before counting colons so g:a:v!pom stays pinned.
        int bang = trimmed.indexOf('!');
        String withoutType = bang >= 0 ? trimmed.substring(0, bang) : trimmed;
        int at = withoutType.indexOf('@');
        String body = at >= 0 ? withoutType.substring(0, at) : withoutType;
        long colons = body.chars().filter(c -> c == ':').count();
        if (colons == 1) {
            int colon = body.indexOf(':');
            String group = body.substring(0, colon);
            String artifact = body.substring(colon + 1);
            if (group.isBlank() || artifact.isBlank()) {
                throw new IllegalArgumentException("tool coordinate must be group:artifact[…], got: " + spec);
            }
            if (bang >= 0) {
                throw new IllegalArgumentException(
                        "packaging type ('!') requires a full g:a:v coordinate, got: " + spec);
            }
            VersionSelector selector = at >= 0
                    ? VersionSelector.parseFloating(withoutType.substring(at + 1))
                    : VersionSelector.parse("latest");
            return new Floating(group, artifact, selector, spec);
        }
        // 2+ colons (or none — Coordinate.parse renders the canonical error).
        return new Pinned(Coordinate.parse(trimmed), spec);
    }
}
