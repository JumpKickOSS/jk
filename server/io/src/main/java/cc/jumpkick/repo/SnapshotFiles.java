// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static cc.jumpkick.host.DomXml.childElement;
import static cc.jumpkick.host.DomXml.childElements;
import static cc.jumpkick.host.DomXml.childText;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.DomXml;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * Where a repository that deploys snapshots in Maven's unique form keeps a {@code -SNAPSHOT} file:
 * {@code a-1.0-20261025.201500-3.jar}, named by the {@code maven-metadata.xml} in the version's
 * directory.
 */
final class SnapshotFiles {

    private SnapshotFiles() {}

    /** Reads a file of the repository by its path relative to the repository's root; empty when absent. */
    @FunctionalInterface
    interface Repository {
        Optional<byte[]> read(String relativePath) throws IOException, InterruptedException;
    }

    /**
     * Where {@code repository} keeps {@code relativePath}: a {@code -SNAPSHOT} version's file under
     * the timestamped name its version directory's {@code maven-metadata.xml} lists ({@link
     * #stamped}), as {@code jk publish} deploys a snapshot; else {@code relativePath} itself, as
     * {@code mvn install} keeps a snapshot and as every release is kept.
     */
    static String source(String relativePath, Repository repository) throws InterruptedException {
        String metadata = metadataPath(relativePath);
        if (relativePath.equals(metadata) || !metadata.endsWith("-SNAPSHOT/maven-metadata.xml")) return relativePath;
        try {
            return repository
                    .read(metadata)
                    .flatMap(xml -> stamped(relativePath, xml))
                    .orElse(relativePath);
        } catch (IOException unreadable) {
            return relativePath;
        }
    }

    /** The {@code maven-metadata.xml} beside {@code relativePath}, a file in a version directory. */
    static String metadataPath(String relativePath) {
        return relativePath.substring(0, relativePath.lastIndexOf('/') + 1) + "maven-metadata.xml";
    }

    /**
     * {@code relativePath} ({@code g/a/1.0-SNAPSHOT/a-1.0-SNAPSHOT[-classifier].ext}) under the
     * timestamped name {@code metadata} lists for it; empty when the metadata names none, or says
     * the files keep their {@code -SNAPSHOT} names ({@code <localCopy>true}).
     */
    static Optional<String> stamped(String relativePath, byte[] metadata) {
        int slash = relativePath.lastIndexOf('/');
        int versionSlash = slash <= 0 ? -1 : relativePath.lastIndexOf('/', slash - 1);
        int artifactSlash = versionSlash <= 0 ? -1 : relativePath.lastIndexOf('/', versionSlash - 1);
        if (artifactSlash < 0) return Optional.empty();
        String version = relativePath.substring(versionSlash + 1, slash);
        String artifact = relativePath.substring(artifactSlash + 1, versionSlash);
        String file = relativePath.substring(slash + 1);
        String stem = artifact + "-" + version;
        if (!version.endsWith("-SNAPSHOT") || !file.startsWith(stem)) return Optional.empty();
        String rest = file.substring(stem.length());
        Element versioning;
        try {
            versioning = childElement(DomXml.parse(metadata).getDocumentElement(), "versioning");
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
        Element snapshot = childElement(versioning, "snapshot");
        if (EnvValues.parseBool(childText(snapshot, "localCopy")).orElse(false)) return Optional.empty();
        String value = null;
        for (Element entry : childElements(childElement(versioning, "snapshotVersions"), "snapshotVersion")) {
            String classifier = trim(childText(entry, "classifier"));
            String extension = trim(childText(entry, "extension"));
            String suffix = (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
            if (extension != null && rest.equals(suffix)) {
                value = trim(childText(entry, "value"));
                break;
            }
        }
        if (value == null) {
            String timestamp = trim(childText(snapshot, "timestamp"));
            String build = trim(childText(snapshot, "buildNumber"));
            if (timestamp == null || build == null) return Optional.empty();
            value = version.substring(0, version.length() - "-SNAPSHOT".length()) + "-" + timestamp + "-" + build;
        }
        if (value.isEmpty() || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) return Optional.empty();
        return Optional.of(relativePath.substring(0, slash + 1) + artifact + "-" + value + rest);
    }

    private static @Nullable String trim(@Nullable String s) {
        return s == null ? null : s.strip();
    }
}
