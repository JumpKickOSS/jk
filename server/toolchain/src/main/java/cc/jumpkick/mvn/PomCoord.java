// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.host.DomXml;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * {@code groupId:artifactId} of the {@code pom.xml} in a directory — a Maven-only project's
 * coordinate for the journal and the project card. The group falls back to {@code <parent>}'s.
 */
public final class PomCoord {

    /** Maven's project file name. */
    public static final String POM = "pom.xml";

    private PomCoord() {}

    /** The coordinate, or {@code null} when the directory has no readable POM with an artifactId. */
    public static @Nullable String of(Path dir) {
        Path pom = dir.resolve(POM);
        if (!Files.isRegularFile(pom)) return null;
        try {
            Element project = DomXml.parse(pom).getDocumentElement();
            String artifact = DomXml.childText(project, "artifactId");
            String group = DomXml.childText(project, "groupId");
            if (group == null) group = DomXml.childText(DomXml.childElement(project, "parent"), "groupId");
            if (artifact == null || artifact.isBlank() || group == null || group.isBlank()) return null;
            return group.strip() + ":" + artifact.strip();
        } catch (Exception e) {
            return null;
        }
    }
}
