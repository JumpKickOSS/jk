// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.resolver.VersionSelectors;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The Maven version floor a project's root {@code pom.xml} states through the enforcer plugin's
 * {@code requireMavenVersion} rule: {@code <version>[3.9.11,)</version>}, {@code
 * <version>3.9.11</version>} (Maven reads a bare version as that version or newer) or a property
 * reference the POM's own {@code <properties>} resolve. Several rules must all hold, so the
 * highest floor wins. Nothing beyond the root POM is read: a parent outside the checkout is not
 * consulted.
 */
public final class EnforcerMavenVersion {

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private EnforcerMavenVersion() {}

    /** The floor the POM in {@code projectDir} states, or {@code null} when it states none jk can read. */
    public static @Nullable String minimum(Path projectDir) {
        Path pom = projectDir.resolve(PomCoord.POM);
        if (!Files.isRegularFile(pom)) return null;
        try {
            return minimum(DomXml.parse(pom).getDocumentElement());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    static @Nullable String minimum(Element project) {
        Element properties = DomXml.childElement(project, "properties");
        String floor = null;
        NodeList rules = project.getElementsByTagName("requireMavenVersion");
        for (int i = 0; i < rules.getLength(); i++) {
            Node rule = rules.item(i);
            if (!(rule instanceof Element e)) continue;
            String spec = DomXml.childText(e, "version");
            if (spec == null) continue;
            String lower = lowerBound(interpolate(spec.strip(), properties));
            if (lower == null) continue;
            if (floor == null || Versions.compare(lower, floor) > 0) floor = lower;
        }
        return floor;
    }

    /** {@code ${name}} references replaced from the POM's own properties; an unknown one is left as is. */
    static String interpolate(String spec, @Nullable Element properties) {
        Matcher m = PROPERTY.matcher(spec);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = properties == null ? null : DomXml.childText(properties, m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value.strip()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The lowest version {@code spec} admits: the version itself when bare, the first range's lower
     * bound otherwise; {@code null} when the spec is unbounded below, unreadable or still carries an
     * unresolved property.
     */
    static @Nullable String lowerBound(String spec) {
        if (spec.isEmpty() || spec.contains("${")) return null;
        char first = spec.charAt(0);
        if (Character.isDigit(first)) return spec;
        try {
            VersionSet set = VersionSelectors.parseRange(spec);
            return switch (set) {
                case VersionSet.Range r -> r.min();
                case VersionSet.Union u ->
                    u.parts().stream()
                            .map(VersionSet.Range::min)
                            .filter(v -> v != null)
                            .min(Versions::compare)
                            .orElse(null);
                default -> null;
            };
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
