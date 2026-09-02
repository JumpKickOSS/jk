// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.MavenPackaging;
import java.nio.file.Path;

/**
 * Maps coordinates to Maven repository paths.
 *
 * <p>Layout (per Maven Central convention): {@code
 * <group-with-slashes>/<artifact>/<version>/<artifact>-<version>[-<classifier>].<ext>}
 */
public final class MavenLayout {

    private MavenLayout() {}

    /**
     * Resolve {@code relativePath} under {@code root}, refusing any result that escapes it —
     * absolute segments, {@code ..} traversal, or a leading {@code /}. GAV coordinates and repo
     * names come from a (possibly hostile) cloned project's lockfile and flow into real store and
     * {@code ~/.m2} writes, so every write sink must resolve through this guard.
     */
    public static Path safeResolve(Path root, String relativePath) {
        Path base = root.normalize();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("path escapes store root: '" + relativePath + "' under " + root);
        }
        return resolved;
    }

    /**
     * Reject a single path segment (repo name, classifier, …) that could break out of the layout:
     * blank, a path separator, a null byte, {@code .}/{@code ..}, or a leading {@code ~}.
     */
    public static String requireSafeSegment(String segment, String what) {
        if (segment == null
                || segment.isBlank()
                || segment.indexOf('/') >= 0
                || segment.indexOf('\\') >= 0
                || segment.indexOf('\0') >= 0
                || segment.equals(".")
                || segment.equals("..")
                || segment.startsWith("~")) {
            throw new IllegalArgumentException("unsafe " + what + ": '" + segment + "'");
        }
        return segment;
    }

    /**
     * Relative path to the primary artifact for this coordinate. The extension comes from the
     * packaging type, which is not always the type name — {@code test-jar} publishes as
     * {@code artifact-version-tests.jar}.
     */
    public static String artifactPath(Coordinate coord) {
        return basePath(coord) + filename(coord, MavenPackaging.extensionOf(coord.type()), true);
    }

    /**
     * Relative path to the POM. Maven POMs are never classified — secondary artifacts (e.g.
     * {@code guice:jar:classes}) share the main GAV's {@code artifact-version.pom}. Including a
     * classifier here produced missing paths like {@code guice-5.1.0-classes.pom} and made PubGrub
     * treat valid classifier packages as unavailable.
     */
    public static String pomPath(Coordinate coord) {
        return basePath(coord) + filename(coord, "pom", false);
    }

    /** Relative path to the {@code maven-metadata.xml} for the artifact. */
    public static String metadataPath(Coordinate coord) {
        return coord.group().replace('.', '/') + "/" + coord.artifact() + "/maven-metadata.xml";
    }

    private static String basePath(Coordinate coord) {
        return coord.group().replace('.', '/') + "/" + coord.artifact() + "/" + coord.version() + "/";
    }

    private static String filename(Coordinate coord, String extension, boolean includeClassifier) {
        String classifier = includeClassifier && coord.classifier() != null ? "-" + coord.classifier() : "";
        return coord.artifact() + "-" + coord.version() + classifier + "." + extension;
    }
}
