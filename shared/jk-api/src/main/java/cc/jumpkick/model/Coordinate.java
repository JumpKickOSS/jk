// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Maven coordinate: {@code groupId:artifactId:version[:classifier][!type]}. Type defaults to
 * {@code jar}; classifier defaults to absent. {@code !} marks packaging type; {@code @} is reserved
 * for JumpKick version selectors ({@code g:a@1.2} / {@code g:a@~1.2}); {@code ~} stays a version-range
 * prefix.
 */
public record Coordinate(
        String group,
        String artifact,
        String version,
        @Nullable String classifier,
        String type) {

    public Coordinate {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(type, "type");
        if (group.isBlank()) throw new IllegalArgumentException("group must not be blank");
        if (artifact.isBlank()) throw new IllegalArgumentException("artifact must not be blank");
        if (version.isBlank()) throw new IllegalArgumentException("version must not be blank");
        // Every field becomes a path segment under a store or ~/.m2 root, and the values arrive
        // from a cloned project's lockfile: a segment that could climb out of the layout is refused
        // here, once, instead of at each of the resolves downstream.
        for (String segment : group.split("\\.", -1)) requireSafeSegment(segment, "group");
        requireSafeSegment(artifact, "artifact");
        requireSafeSegment(version, "version");
        if (classifier != null) requireSafeSegment(classifier, "classifier");
        requireSafeSegment(type, "type");
    }

    /**
     * Reject a value that cannot stand as one Maven-layout path segment: blank, a path separator,
     * a null byte, {@code .} / {@code ..}, or a leading {@code ~}.
     */
    private static void requireSafeSegment(String segment, String what) {
        if (segment.isBlank()
                || segment.indexOf('/') >= 0
                || segment.indexOf('\\') >= 0
                || segment.indexOf('\0') >= 0
                || segment.equals(".")
                || segment.equals("..")
                || segment.startsWith("~")) {
            throw new IllegalArgumentException("unsafe " + what + " in coordinate: '" + segment + "'");
        }
    }

    public static Coordinate of(String group, String artifact, String version) {
        return new Coordinate(group, artifact, version, null, "jar");
    }

    /** From {@code group:artifact} plus version (type {@code jar}, no classifier). */
    public static Coordinate ofModule(String module, String version) {
        Objects.requireNonNull(module, "module");
        int colon = module.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("module must be group:artifact, got: " + module);
        }
        return of(module.substring(0, colon), module.substring(colon + 1), version);
    }

    /** Parse a coordinate spec of the form {@code group:artifact:version[:classifier][!type]}. */
    public static Coordinate parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        int at = spec.indexOf('@');
        if (at >= 0) {
            throw new IllegalArgumentException(
                    "packaging type uses '!' (e.g. g:a:1.0!pom); '@' is for version selectors (g:a@latest), got: "
                            + spec);
        }
        String type = "jar";
        String body = spec;
        int bang = spec.indexOf('!');
        if (bang >= 0) {
            type = spec.substring(bang + 1);
            body = spec.substring(0, bang);
            if (type.isBlank()) {
                throw new IllegalArgumentException("packaging type after '!' is blank: " + spec);
            }
        }
        String[] parts = body.split(":", -1);
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException(
                    "coordinate must be group:artifact:version[:classifier][!type], got: " + spec);
        }
        // A trailing colon ("g:a:1.0:") is an absent classifier, not an empty one: an empty
        // classifier would ask the repository for "a-1.0-.jar".
        String classifier = parts.length == 4 && !parts[3].isEmpty() ? parts[3] : null;
        return new Coordinate(parts[0], parts[1], parts[2], classifier, type);
    }

    /** Canonical {@code group:artifact:version} form (omits classifier and type). */
    public String toGav() {
        return group + ":" + artifact + ":" + version;
    }

    /** Module identifier without version: {@code group:artifact}. */
    public String module() {
        return group + ":" + artifact;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(toGav());
        if (classifier != null) sb.append(':').append(classifier);
        if (!"jar".equals(type)) sb.append('!').append(type);
        return sb.toString();
    }
}
