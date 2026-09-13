// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static cc.jumpkick.host.DomXml.childElement;
import static cc.jumpkick.host.DomXml.childElements;
import static cc.jumpkick.host.DomXml.childText;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.pom.PomXml;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * A Maven repository's {@code maven-metadata.xml} — the version list an artifact advertises — in
 * both directions.
 *
 * <p>jk reads this document (the resolver enumerating versions for a floating selector) and also
 * writes it: {@code jk publish} uploads one beside the artifacts, and the git-source materializer
 * installs one into the per-commit {@code file://} repo it builds a source dependency into. Real
 * repository managers synthesize it server-side, which is why the write side only matters for the
 * plain targets jk advertises ({@code file://}, static HTTP, {@code s3://}, {@code gs://}) — and
 * why it is jk that has to get it right there.
 *
 * <p><b>{@link #render} and {@link #parse} are inverses, and that is the point of one owner.</b>
 * Every value goes out through {@link PomXml#escape} — the same escaper the POM published beside it
 * uses — and comes back in through a hardened DOM, so a coordinate or a git tag carrying {@code &}
 * or {@code <} survives a publish instead of writing a document no resolver can read. Two writers
 * mean one of them escapes and the other does not; two readers mean a republish either double-
 * escapes what the last one wrote or fails to see it at all.
 */
public record MavenMetadata(
        @Nullable String groupId,
        String artifactId,
        List<String> versions,
        @Nullable String latest,
        @Nullable String release) {

    public MavenMetadata {
        Objects.requireNonNull(artifactId, "artifactId");
        versions = List.copyOf(versions);
    }

    /** A coordinate with nothing published yet — the starting point {@link #withVersion} builds on. */
    public static MavenMetadata empty(@Nullable String groupId, String artifactId) {
        return new MavenMetadata(groupId, artifactId, List.of(), null, null);
    }

    public static MavenMetadata parse(byte[] xml) {
        Document doc;
        try {
            doc = DomXml.parse(xml);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to parse maven-metadata.xml: " + e.getMessage(), e);
        }
        return fromDocument(doc);
    }

    /**
     * This document with {@code version} listed, version-sorted, and {@code <latest>}/{@code
     * <release>} recomputed: {@code <latest>} is the newest version of any kind, {@code <release>}
     * the newest non-SNAPSHOT (null — element omitted — when every version is a snapshot). Maven's
     * convention, and the resolver relies on it for floating selectors.
     *
     * <p>Publishing 0.2.0 after 0.1.0 must leave both listed, so this merges rather than replaces:
     * the caller parses what the repository already serves and adds to it.
     */
    public MavenMetadata withVersion(String version) {
        Objects.requireNonNull(version, "version");
        List<String> merged = new ArrayList<>(versions);
        if (!merged.contains(version)) merged.add(version);
        merged.sort(Versions::compare);
        String newRelease = merged.stream()
                .filter(v -> !v.endsWith("-SNAPSHOT"))
                .reduce((a, b) -> b)
                .orElse(null);
        return new MavenMetadata(groupId, artifactId, merged, merged.get(merged.size() - 1), newRelease);
    }

    /** The document, UTF-8 encoded as its own declaration says. Exactly inverted by {@link #parse}. */
    public byte[] render() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n");
        if (groupId != null) {
            sb.append("  <groupId>").append(PomXml.escape(groupId)).append("</groupId>\n");
        }
        sb.append("  <artifactId>").append(PomXml.escape(artifactId)).append("</artifactId>\n");
        sb.append("  <versioning>\n");
        if (latest != null) {
            sb.append("    <latest>").append(PomXml.escape(latest)).append("</latest>\n");
        }
        if (release != null) {
            sb.append("    <release>").append(PomXml.escape(release)).append("</release>\n");
        }
        sb.append("    <versions>\n");
        for (String v : versions) {
            sb.append("      <version>").append(PomXml.escape(v)).append("</version>\n");
        }
        sb.append("    </versions>\n");
        sb.append("  </versioning>\n</metadata>\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static MavenMetadata fromDocument(Document doc) {
        Element metadata = doc.getDocumentElement();
        if (metadata == null || !"metadata".equals(metadata.getNodeName())) {
            throw new IllegalArgumentException(
                    "expected <metadata> root, got: " + (metadata == null ? "<none>" : metadata.getNodeName()));
        }
        String groupId = childText(metadata, "groupId");
        String artifactId = childText(metadata, "artifactId");
        if (artifactId == null) {
            throw new IllegalArgumentException("maven-metadata.xml missing <artifactId>");
        }
        Element versioning = childElement(metadata, "versioning");
        String latest = versioning == null ? null : childText(versioning, "latest");
        String release = versioning == null ? null : childText(versioning, "release");
        List<String> versions = new ArrayList<>();
        if (versioning != null) {
            Element versionsElement = childElement(versioning, "versions");
            if (versionsElement != null) {
                for (Element v : childElements(versionsElement, "version")) {
                    String text = v.getTextContent().trim();
                    if (!text.isEmpty()) versions.add(text);
                }
            }
        }
        return new MavenMetadata(groupId, artifactId, versions, latest, release);
    }
}
