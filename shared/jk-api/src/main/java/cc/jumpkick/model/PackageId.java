// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * Solver / lock package identity: {@code group:artifact:type:classifier}. Defaults: type
 * {@code jar}, classifier empty ({@code g:a:jar:}). Bare {@code group:artifact} parses as the
 * default jar. BOM management and Maven exclusions stay GA-scoped via {@link #ga()}.
 *
 * <p>Maven's {@code test-jar} type is the jar under the {@code tests} classifier, so an id spelled
 * with that type keys as {@code g:a:jar:tests} (or {@code g:a:jar:<classifier>} when the edge
 * names one): a POM edge, a manifest {@code kind = "tests"} root and a {@code tests} classifier
 * are one package, and the lock fetches {@code a-v-tests.jar} for all of them.
 */
public final class PackageId {

    public static final String DEFAULT_TYPE = "jar";

    /** Maven's packaging type for a module's test classes; see the class note. */
    public static final String TEST_JAR_TYPE = "test-jar";

    /** The classifier Maven publishes a {@link #TEST_JAR_TYPE} under. */
    public static final String TESTS_CLASSIFIER = "tests";

    private final String group;
    private final String artifact;
    private final String type;
    private final String classifier; // never null; empty when absent

    private PackageId(String group, String artifact, String type, String classifier) {
        this.group = Objects.requireNonNull(group, "group");
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        String t = (type == null || type.isBlank()) ? DEFAULT_TYPE : type;
        String c = classifier == null ? "" : classifier;
        if (TEST_JAR_TYPE.equals(t)) {
            t = DEFAULT_TYPE;
            if (c.isEmpty()) c = TESTS_CLASSIFIER;
        }
        this.type = t;
        this.classifier = c;
        if (group.isBlank() || artifact.isBlank()) {
            throw new IllegalArgumentException("group and artifact must be non-blank");
        }
    }

    public static PackageId of(String group, String artifact, String type, String classifier) {
        return new PackageId(group, artifact, type, classifier);
    }

    /** The test-jar of {@code group:artifact}: {@code g:a:jar:tests}. */
    public static PackageId testJar(String group, String artifact) {
        return new PackageId(group, artifact, TEST_JAR_TYPE, TESTS_CLASSIFIER);
    }

    /** Default jar, no classifier from a {@code group:artifact} module string. */
    public static PackageId ofGa(String groupArtifact) {
        Objects.requireNonNull(groupArtifact, "groupArtifact");
        int colon = groupArtifact.indexOf(':');
        if (colon <= 0 || colon != groupArtifact.lastIndexOf(':')) {
            throw new IllegalArgumentException("expected group:artifact, got: " + groupArtifact);
        }
        return new PackageId(groupArtifact.substring(0, colon), groupArtifact.substring(colon + 1), DEFAULT_TYPE, "");
    }

    /**
     * Parse a package key. Accepts {@code g:a} (declared modules) or full
     * {@code g:a:type:classifier} (classifier may be empty).
     */
    public static PackageId parse(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) throw new IllegalArgumentException("package id is blank");
        // Workspace / git / path synthetic modules stay as opaque single-colon forms when GA-shaped;
        // multi-colon non-identity keys are not PackageIds — callers keep them as-is elsewhere.
        String[] parts = key.split(":", -1);
        if (parts.length == 2) {
            return new PackageId(parts[0], parts[1], DEFAULT_TYPE, "");
        }
        if (parts.length == 4) {
            return new PackageId(parts[0], parts[1], parts[2], parts[3]);
        }
        if (parts.length == 3) {
            // g:a:type with implicit empty classifier
            return new PackageId(parts[0], parts[1], parts[2], "");
        }
        throw new IllegalArgumentException(
                "package id must be group:artifact or group:artifact:type:classifier, got: " + key);
    }

    /** True when {@code key} looks like a Maven package id (2–4 colon-separated segments). */
    public static boolean isMavenPackageKey(String key) {
        if (key == null || key.isBlank()) return false;
        if (key.startsWith(Dependency.WORKSPACE_PREFIX)
                || key.startsWith(Dependency.GIT_PREFIX)
                || key.startsWith(Dependency.PATH_PREFIX)) {
            return false;
        }
        int n = 1;
        for (int i = 0; i < key.length(); i++) if (key.charAt(i) == ':') n++;
        return n >= 2 && n <= 4;
    }

    public String group() {
        return group;
    }

    public String artifact() {
        return artifact;
    }

    public String type() {
        return type;
    }

    /** Empty string when no classifier. */
    public String classifier() {
        return classifier;
    }

    /** {@code group:artifact} — BOM / exclusion scope. */
    public String ga() {
        return group + ":" + artifact;
    }

    /** Canonical solver/lock key: {@code group:artifact:type:classifier}. */
    public String key() {
        return group + ":" + artifact + ":" + type + ":" + classifier;
    }

    public boolean isDefaultJar() {
        return DEFAULT_TYPE.equals(type) && classifier.isEmpty();
    }

    /**
     * Human display: {@code g:a}, or {@code g:a:classifier}, or {@code g:a!type} when non-default.
     * Uses {@code !} for packaging type so {@code @} stays version selectors and {@code ~} stays
     * version-range prefixes.
     */
    public String display() {
        StringBuilder sb = new StringBuilder(ga());
        if (!classifier.isEmpty()) sb.append(':').append(classifier);
        if (!DEFAULT_TYPE.equals(type)) sb.append('!').append(type);
        return sb.toString();
    }

    public Coordinate withVersion(String version) {
        return new Coordinate(group, artifact, version, classifier.isEmpty() ? null : classifier, type);
    }

    @Override
    public String toString() {
        return key();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PackageId that)) return false;
        return group.equals(that.group)
                && artifact.equals(that.artifact)
                && type.equals(that.type)
                && classifier.equals(that.classifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(group, artifact, type, classifier);
    }
}
