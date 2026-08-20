// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * Maven coordinate: {@code groupId:artifactId:version[:classifier][!type]}. Type defaults to
 * {@code jar}; classifier defaults to absent. {@code !} marks packaging type; {@code @} is reserved
 * for JumpKick version selectors ({@code g:a@1.2} / {@code g:a@~1.2}); {@code ~} stays a version-range
 * prefix.
 */
public record Coordinate(String group, String artifact, String version, String classifier, String type) {

    public Coordinate {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(type, "type");
        if (group.isBlank()) throw new IllegalArgumentException("group must not be blank");
        if (artifact.isBlank()) throw new IllegalArgumentException("artifact must not be blank");
        if (version.isBlank()) throw new IllegalArgumentException("version must not be blank");
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
        String classifier = parts.length == 4 ? parts[3] : null;
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
