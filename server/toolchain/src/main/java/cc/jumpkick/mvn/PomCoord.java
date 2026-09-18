// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.host.DomXml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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

    /**
     * Why the POM in {@code dir} cannot name its project, as one sentence naming the file and the
     * element it lacks — a {@code <version>}, {@code <groupId>} or {@code <artifactId>} neither it
     * nor its {@code <parent>} carries; empty when it names one, or when there is no POM to read.
     */
    public static Optional<String> missingIdentity(Path dir) {
        Path pom = dir.resolve(POM);
        if (!Files.isRegularFile(pom)) return Optional.empty();
        Element project;
        try {
            project = DomXml.parse(pom).getDocumentElement();
        } catch (Exception e) {
            return Optional.empty();
        }
        Element parent = DomXml.childElement(project, "parent");
        if (blank(DomXml.childText(project, "groupId")) && blank(DomXml.childText(parent, "groupId"))) {
            return Optional.of(pom + " has no <groupId> and no <parent><groupId>");
        }
        if (blank(DomXml.childText(project, "artifactId"))) return Optional.of(pom + " has no <artifactId>");
        if (blank(DomXml.childText(project, "version")) && blank(DomXml.childText(parent, "version"))) {
            return Optional.of(pom + " has no <version> and no <parent><version>");
        }
        return Optional.empty();
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }

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
