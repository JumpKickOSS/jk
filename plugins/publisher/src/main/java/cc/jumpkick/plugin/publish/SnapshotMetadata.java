// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static cc.jumpkick.host.DomXml.childElement;
import static cc.jumpkick.host.DomXml.childElements;
import static cc.jumpkick.host.DomXml.childText;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.pom.PomXml;
import cc.jumpkick.repo.ObjectStoreTransport.Revision;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The build number and latest value for each classifier and extension of a Maven snapshot. */
record SnapshotMetadata(int build, Map<Key, Entry> entries) {

    record Key(String classifier, String extension) {}

    record Entry(String value, String updated) {}

    static SnapshotMetadata read(Optional<Revision> previous, URI uri) throws IOException {
        Map<Key, Entry> entries = new LinkedHashMap<>();
        if (previous.isEmpty()) return new SnapshotMetadata(0, entries);
        try {
            var metadata = DomXml.parse(previous.get().body()).getDocumentElement();
            if (!"metadata".equals(metadata.getNodeName())) throw new IOException("expected <metadata>");
            var versioning = childElement(metadata, "versioning");
            String number = childText(childElement(versioning, "snapshot"), "buildNumber");
            int build = number == null ? 0 : Integer.parseInt(number);
            if (build < 0) throw new IOException("invalid build number");
            for (var entry : childElements(childElement(versioning, "snapshotVersions"), "snapshotVersion")) {
                String extension = childText(entry, "extension");
                String value = childText(entry, "value");
                String updated = childText(entry, "updated");
                if (extension == null || value == null || updated == null) {
                    throw new IOException("incomplete snapshot version");
                }
                String classifier = Objects.requireNonNullElse(childText(entry, "classifier"), "");
                entries.put(new Key(classifier, extension), new Entry(value, updated));
            }
            return new SnapshotMetadata(build, entries);
        } catch (IOException | IllegalArgumentException e) {
            throw new IOException(uri + ": could not read snapshot metadata: " + e.getMessage(), e);
        }
    }

    static String renderEntries(Map<Key, Entry> entries) {
        StringBuilder xml = new StringBuilder();
        entries.forEach((key, entry) -> {
            xml.append("      <snapshotVersion>\n");
            if (!key.classifier().isEmpty()) {
                xml.append("        <classifier>")
                        .append(PomXml.escape(key.classifier()))
                        .append("</classifier>\n");
            }
            xml.append("        <extension>")
                    .append(PomXml.escape(key.extension()))
                    .append("</extension>\n")
                    .append("        <value>")
                    .append(PomXml.escape(entry.value()))
                    .append("</value>\n")
                    .append("        <updated>")
                    .append(PomXml.escape(entry.updated()))
                    .append("</updated>\n")
                    .append("      </snapshotVersion>\n");
        });
        return xml.toString();
    }
}
