// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static cc.jumpkick.host.DomXml.childElement;
import static cc.jumpkick.host.DomXml.childElements;
import static cc.jumpkick.host.DomXml.childText;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.DomXml;
import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Parses a single Maven POM with intra-POM property substitution. Parent/BOM/profiles are
 * resolver-stage concerns. The document comes from {@link DomXml}, which owns jk's XXE posture.
 */
public final class PomParser {

    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)\\}");

    private PomParser() {}

    public static Pom parse(byte[] xml) {
        return parse(parseXml(xml));
    }

    public static Pom parse(InputStream xml) {
        try {
            return parse(DomXml.parse(xml));
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    public static Pom parse(String xml) {
        try {
            return parse(DomXml.parse(xml));
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    /**
     * The document behind a POM, for a caller that needs the raw DOM as well — {@code PomImporter}
     * reads constructs this parser drops (profiles, plugins, {@code <modules>}) and would otherwise
     * parse every POM twice.
     */
    public static Document parseXml(byte[] xml) {
        try {
            return DomXml.parse(xml);
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    // --- XML parsing -------------------------------------------------------

    private static PomParseException unreadable(IOException e) {
        return new PomParseException("failed to parse POM: " + e.getMessage(), e);
    }

    /** The POM in an already-parsed document. */
    public static Pom parse(Document doc) {
        Element project = doc.getDocumentElement();
        if (project == null || !"project".equals(project.getNodeName())) {
            throw new PomParseException(
                    "POM root element must be <project>, got: " + (project == null ? "<none>" : project.getNodeName()));
        }

        Pom.Parent parent = parseParent(project);

        String groupId = childText(project, "groupId");
        String artifactId = childText(project, "artifactId");
        String version = childText(project, "version");
        String packaging = childText(project, "packaging");
        if (packaging == null) packaging = "jar";

        if (artifactId == null) {
            throw new PomParseException("POM missing required <artifactId>");
        }

        // Inherit groupId / version from <parent> when absent.
        if (groupId == null && parent != null) groupId = parent.groupId();
        if (version == null && parent != null) version = parent.version();

        Map<String, String> properties = parseProperties(project);

        // Build substitution context: project.* + named properties.
        Map<String, String> ctx = new LinkedHashMap<>(properties);
        if (groupId != null) ctx.put("project.groupId", groupId);
        ctx.put("project.artifactId", artifactId);
        if (version != null) ctx.put("project.version", version);
        ctx.put("project.packaging", packaging);
        if (parent != null) {
            ctx.put("project.parent.groupId", parent.groupId());
            ctx.put("project.parent.artifactId", parent.artifactId());
            ctx.put("project.parent.version", parent.version());
        }

        List<Pom.Dep> deps = parseDependencies(childElement(project, "dependencies"), ctx);
        List<Pom.Dep> managed =
                parseDependencies(childElement(childElement(project, "dependencyManagement"), "dependencies"), ctx);

        String gav = substituteOrNull(groupId, ctx) + ":" + substitute(artifactId, ctx) + ":"
                + substituteOrNull(version, ctx);
        return new Pom(
                substituteOrNull(groupId, ctx),
                substitute(artifactId, ctx),
                substituteOrNull(version, ctx),
                substitute(packaging, ctx),
                parent,
                properties,
                deps,
                managed,
                parseRelocation(project, ctx),
                parseRepositories(project, ctx, gav));
    }

    /**
     * The POM's own {@code <repositories>}: the top-level list plus those of every profile Maven
     * would activate with nothing on the command line. Central is left out, an {@code <id>}
     * defaults to the URL, and the {@code <releases>} / {@code <snapshots>} policies ride along.
     */
    private static List<Pom.Repository> parseRepositories(Element project, Map<String, String> ctx, String gav) {
        List<Pom.Repository> out = new ArrayList<>();
        addRepositories(childElement(project, "repositories"), ctx, gav, out);
        Element profiles = childElement(project, "profiles");
        if (profiles != null) {
            for (Element profile : childElements(profiles, "profile")) {
                if (activeWithoutCommandLine(childElement(profile, "activation"))) {
                    addRepositories(childElement(profile, "repositories"), ctx, gav, out);
                }
            }
        }
        return out;
    }

    /**
     * {@code <activeByDefault>true</activeByDefault>}, or a {@code <property>} whose name is
     * negated ({@code !skipDefault}) with no value and no other condition: true on every machine
     * that passes no {@code -D}. An OS, JDK or file condition is not judged here.
     */
    private static boolean activeWithoutCommandLine(@Nullable Element activation) {
        if (activation == null) return false;
        if (EnvValues.parseBool(childText(activation, "activeByDefault")).orElse(false)) return true;
        Element property = childElement(activation, "property");
        if (property == null) return false;
        if (childElement(activation, "os") != null
                || childElement(activation, "jdk") != null
                || childElement(activation, "file") != null) {
            return false;
        }
        String name = childText(property, "name");
        return name != null && name.startsWith("!") && childText(property, "value") == null;
    }

    private static void addRepositories(
            @Nullable Element repositories, Map<String, String> ctx, String gav, List<Pom.Repository> out) {
        if (repositories == null) return;
        for (Element repository : childElements(repositories, "repository")) {
            String url = substituteOrNull(childText(repository, "url"), ctx);
            if (url == null || url.isBlank() || url.contains("${") || isCentral(url.trim())) continue;
            boolean releases = policyEnabled(childElement(repository, "releases"));
            boolean snapshots = policyEnabled(childElement(repository, "snapshots"));
            if (!releases && !snapshots) continue;
            String id = childText(repository, "id");
            out.add(new Pom.Repository(
                    id == null || id.isBlank() ? url.trim() : id.trim(), url.trim(), gav, releases, snapshots));
        }
    }

    /** {@code <releases>} / {@code <snapshots>}: {@code <enabled>} as written, on when the element is absent. */
    private static boolean policyEnabled(@Nullable Element policy) {
        return policy == null
                || EnvValues.parseBool(childText(policy, "enabled")).orElse(true);
    }

    /** Maven Central is in every group already. */
    static boolean isCentral(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null
                    && host.equalsIgnoreCase(RepositorySpec.MAVEN_CENTRAL.url().getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** {@code <distributionManagement><relocation>} — absent for all but renamed artifacts. */
    private static Pom.@Nullable Relocation parseRelocation(Element project, Map<String, String> ctx) {
        Element rel = childElement(childElement(project, "distributionManagement"), "relocation");
        if (rel == null) return null;
        return new Pom.Relocation(
                substituteOrNull(childText(rel, "groupId"), ctx),
                substituteOrNull(childText(rel, "artifactId"), ctx),
                substituteOrNull(childText(rel, "version"), ctx),
                substituteOrNull(childText(rel, "message"), ctx));
    }

    private static Pom.@Nullable Parent parseParent(Element project) {
        Element parent = childElement(project, "parent");
        if (parent == null) return null;
        String g = required(parent, "groupId", "<parent>");
        String a = required(parent, "artifactId", "<parent>");
        String v = required(parent, "version", "<parent>");
        return new Pom.Parent(g, a, v);
    }

    private static Map<String, String> parseProperties(Element project) {
        Element propsElement = childElement(project, "properties");
        if (propsElement == null) return Map.of();
        Map<String, String> props = new LinkedHashMap<>();
        for (Element child : childElements(propsElement)) {
            props.put(child.getNodeName(), child.getTextContent().trim());
        }
        return props;
    }

    private static List<Pom.Dep> parseDependencies(@Nullable Element deps, Map<String, String> ctx) {
        if (deps == null) return List.of();
        List<Pom.Dep> result = new ArrayList<>();
        for (Element dep : childElements(deps, "dependency")) {
            String g = required(dep, "groupId", "<dependency>");
            String a = required(dep, "artifactId", "<dependency>");
            String v = childText(dep, "version");
            String scope = childText(dep, "scope");
            String optionalStr = childText(dep, "optional");
            String classifier = childText(dep, "classifier");
            String type = childText(dep, "type");

            List<Pom.Dep.Exclusion> exclusions = parseExclusions(childElement(dep, "exclusions"));
            result.add(new Pom.Dep(
                    substitute(g, ctx),
                    substitute(a, ctx),
                    substituteOrNull(v, ctx),
                    substituteOrNull(scope, ctx),
                    "true".equalsIgnoreCase(optionalStr),
                    substituteOrNull(classifier, ctx),
                    substituteOrNull(type, ctx),
                    exclusions));
        }
        return result;
    }

    private static List<Pom.Dep.Exclusion> parseExclusions(@Nullable Element exclusions) {
        if (exclusions == null) return List.of();
        List<Pom.Dep.Exclusion> result = new ArrayList<>();
        for (Element e : childElements(exclusions, "exclusion")) {
            result.add(new Pom.Dep.Exclusion(
                    required(e, "groupId", "<exclusion>"), required(e, "artifactId", "<exclusion>")));
        }
        return result;
    }

    // --- substitution ------------------------------------------------------

    private static String substitute(String raw, Map<String, String> ctx) {
        return Objects.requireNonNull(substituteOrNull(raw, ctx));
    }

    private static @Nullable String substituteOrNull(@Nullable String raw, Map<String, String> ctx) {
        if (raw == null) return null;
        Matcher m = PROPERTY_REF.matcher(raw);
        if (!m.find()) return raw;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = ctx.get(key);
            String replacement = value != null ? value : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        // Second pass to resolve nested references like ${spring.version} → ${other}.
        // One pass is enough for v0.1; deep chains can wait for the resolver.
        return sb.toString();
    }

    // --- DOM helpers -------------------------------------------------------

    private static String required(Element parent, String name, String contextLabel) {
        String value = childText(parent, name);
        if (value == null) {
            throw new PomParseException(contextLabel + " missing required <" + name + ">");
        }
        return value;
    }
}
