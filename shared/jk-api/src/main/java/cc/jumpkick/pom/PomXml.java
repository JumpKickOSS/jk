// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.pom;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Shared {@code pom.xml} primitives for {@code PublishablePom} and {@code PomExporter}: preamble,
 * entity escape, scope mapping, dependency emitters. Lives in {@code jk-api} so publisher need not
 * depend on {@code toolchain-jdk}. Emitters never lead with a blank line.
 */
public final class PomXml {

    private PomXml() {}

    private static final String PREAMBLE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
            + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
            + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 "
            + "https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n\n";

    /** The {@code <?xml?>} + {@code <project>} open + {@code <modelVersion>} header. */
    public static void appendPreamble(StringBuilder sb) {
        sb.append(PREAMBLE);
    }

    /** XML entity-escape for element text (also escapes quotes — harmless, and what both writers did). */
    public static String escape(@Nullable String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** jk {@link Scope} → Maven {@code <scope>} string, or {@code null} for compile / non-emitted. */
    public static @Nullable String mavenScope(Scope s) {
        return switch (s) {
            case EXPORT, MAIN -> null; // compile scope (Maven default — transitive to consumers)
            case RUNTIME -> "runtime";
            case PROVIDED -> "provided";
            case TEST -> "test";
            case PLATFORM, MANAGED, PROCESSOR, TEST_PROCESSOR -> null; // emitted elsewhere
            case PLUGIN -> null; // a plugin worker's floor is the consumer's, never a published edge
            // Dev-loop scopes never publish/export: a POM must not leak development-only deps.
            case DEV, TEST_DEV -> null;
        };
    }

    /** One {@code <dependency>} under {@code <dependencies>}; {@code mavenScope} null → no scope element. */
    public static void appendDependency(
            StringBuilder sb, String group, String artifact, String version, @Nullable String mavenScope) {
        appendDependency(sb, group, artifact, version, mavenScope, null, null);
    }

    /**
     * As {@link #appendDependency(StringBuilder, String, String, String, String)} with optional Maven
     * {@code <type>} / {@code <classifier>} (e.g. test-jar / tests for {@code kind = "tests"}).
     */
    public static void appendDependency(
            StringBuilder sb,
            String group,
            String artifact,
            String version,
            @Nullable String mavenScope,
            @Nullable String type,
            @Nullable String classifier) {
        appendDependency(sb, group, artifact, version, mavenScope, type, classifier, false);
    }

    /** As above; {@code optional} writes Maven's {@code <optional>true</optional>}. */
    private static void appendDependency(
            StringBuilder sb,
            String group,
            String artifact,
            String version,
            @Nullable String mavenScope,
            @Nullable String type,
            @Nullable String classifier,
            boolean optional) {
        sb.append("    <dependency>\n");
        sb.append("      <groupId>").append(escape(group)).append("</groupId>\n");
        sb.append("      <artifactId>").append(escape(artifact)).append("</artifactId>\n");
        sb.append("      <version>").append(escape(version)).append("</version>\n");
        if (type != null && !type.isBlank() && !"jar".equalsIgnoreCase(type)) {
            sb.append("      <type>").append(escape(type)).append("</type>\n");
        }
        if (classifier != null && !classifier.isBlank()) {
            sb.append("      <classifier>").append(escape(classifier)).append("</classifier>\n");
        }
        if (mavenScope != null) {
            sb.append("      <scope>").append(mavenScope).append("</scope>\n");
        }
        if (optional) sb.append("      <optional>true</optional>\n");
        sb.append("    </dependency>\n");
    }

    /** Type and classifier for a tests-kind edge (Maven test-jar); {@code <optional>} for an optional one. */
    public static void appendDependency(StringBuilder sb, Dependency d, String version, @Nullable String mavenScope) {
        if (d.isTestsKind()) {
            appendDependency(sb, d.group(), d.name(), version, mavenScope, "test-jar", "tests", d.optional());
        } else {
            appendDependency(sb, d.group(), d.name(), version, mavenScope, null, null, d.optional());
        }
    }

    /**
     * The {@code <dependencyManagement>} block for BOM-import {@code platforms} ({@code <type>pom</type>
     * <scope>import</scope>}). No-op when empty. {@code version} resolves each platform's version
     * string (the publish POM collapses the selector; the export POM prefers a locked pin and warns).
     */
    public static void appendDependencyManagement(
            StringBuilder sb, List<Dependency> platforms, Function<Dependency, String> version) {
        appendDependencyManagement(sb, platforms, version, List.of());
    }

    /**
     * As {@link #appendDependencyManagement(StringBuilder, List, Function)}, then one managed
     * {@code <dependency>} per coordinate of {@code closure} — the exact version (with classifier
     * and non-jar type when present) a consumer of this POM must use for that module wherever it
     * turns up in the transitive tree. A locally installed worker POM pins its lock's whole runtime
     * closure this way, so a launch rebuilt from the POM runs on the versions the build tested.
     */
    public static void appendDependencyManagement(
            StringBuilder sb,
            List<Dependency> platforms,
            Function<Dependency, String> version,
            Collection<Coordinate> closure) {
        if (platforms.isEmpty() && closure.isEmpty()) return;
        sb.append("  <dependencyManagement>\n    <dependencies>\n");
        for (Dependency d : platforms) {
            sb.append("      <dependency>\n");
            sb.append("        <groupId>").append(escape(d.group())).append("</groupId>\n");
            sb.append("        <artifactId>").append(escape(d.name())).append("</artifactId>\n");
            sb.append("        <version>").append(escape(version.apply(d))).append("</version>\n");
            sb.append("        <type>pom</type>\n");
            sb.append("        <scope>import</scope>\n");
            sb.append("      </dependency>\n");
        }
        for (Coordinate c : closure) {
            sb.append("      <dependency>\n");
            sb.append("        <groupId>").append(escape(c.group())).append("</groupId>\n");
            sb.append("        <artifactId>").append(escape(c.artifact())).append("</artifactId>\n");
            sb.append("        <version>").append(escape(c.version())).append("</version>\n");
            if (!"jar".equalsIgnoreCase(c.type())) {
                sb.append("        <type>").append(escape(c.type())).append("</type>\n");
            }
            if (c.classifier() != null && !c.classifier().isBlank()) {
                sb.append("        <classifier>").append(escape(c.classifier())).append("</classifier>\n");
            }
            sb.append("      </dependency>\n");
        }
        sb.append("    </dependencies>\n  </dependencyManagement>\n");
    }
}
