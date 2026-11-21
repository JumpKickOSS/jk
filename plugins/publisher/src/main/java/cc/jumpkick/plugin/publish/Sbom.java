// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.compile.CycloneDxSbom;
import cc.jumpkick.host.DeterministicZip;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.JkBuild;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The SBOM sidecars of a publish, from {@link JkBuild} and its runtime {@link
 * CycloneDxSbom.Closure}: CycloneDX through the writer every application jar embeds, so a module's
 * sidecar is the document its jar carries, and SPDX 2.3 over the same components and edges.
 * Hand-rolled JSON for a lean worker.
 */
public final class Sbom {

    private static final String ROOT = "SPDXRef-Package-Root";

    private Sbom() {}

    /** Render the CycloneDX JSON SBOM; deterministic for a given project and closure. */
    public static byte[] cyclonedx(JkBuild project, CycloneDxSbom.Closure closure) {
        return CycloneDxSbom.write(
                project.project().group(),
                project.project().name(),
                project.project().version(),
                closure);
    }

    /**
     * Render an SPDX 2.3 JSON SBOM; deterministic like {@link #cyclonedx}: the document {@code
     * DESCRIBES} the root package, which {@code DEPENDS_ON} what the module depends on directly,
     * and each package {@code DEPENDS_ON} its own dependencies. The namespace is a name-based UUID
     * over the packages and relationships, and {@code created} is the instant every entry of a jk
     * jar carries, so a re-run of a publish renders the same bytes.
     */
    public static byte[] spdx(JkBuild project, CycloneDxSbom.Closure closure) {
        StringBuilder packages = new StringBuilder(256);
        packages.append("\"packages\":[");
        appendSpdxPackage(
                packages,
                ROOT,
                project.project().group(),
                project.project().name(),
                project.project().version(),
                null);
        Map<String, String> ids = new HashMap<>();
        int i = 0;
        for (CycloneDxSbom.Component c : closure.components()) {
            String id = "SPDXRef-Package-" + sanitizeId(c.group() + "-" + c.artifact() + "-" + c.version()) + "-" + i++;
            ids.putIfAbsent(c.purl(), id);
            packages.append(',');
            appendSpdxPackage(packages, id, c.group(), c.artifact(), c.version(), c.sha256());
        }
        packages.append(']');
        StringBuilder relationships = new StringBuilder(256);
        relationships.append("\"relationships\":[");
        appendRelationship(relationships, "SPDXRef-DOCUMENT", "DESCRIBES", ROOT);
        for (String to : closure.direct()) {
            String target = ids.get(to);
            if (target != null) appendRelationship(relationships.append(','), ROOT, "DEPENDS_ON", target);
        }
        for (Map.Entry<String, List<String>> e : closure.edges().entrySet()) {
            String from = ids.get(e.getKey());
            if (from == null) continue;
            for (String to : e.getValue()) {
                String target = ids.get(to);
                if (target != null) appendRelationship(relationships.append(','), from, "DEPENDS_ON", target);
            }
        }
        relationships.append(']');
        String docNamespace = "https://jumpkick.build/sbom/"
                + project.project().group()
                + "/"
                + project.project().name()
                + "/"
                + project.project().version()
                + "/"
                + UUID.nameUUIDFromBytes((packages + "," + relationships).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        kv(sb, "spdxVersion", "SPDX-2.3");
        comma(sb);
        kv(sb, "dataLicense", "CC0-1.0");
        comma(sb);
        kv(sb, "SPDXID", "SPDXRef-DOCUMENT");
        comma(sb);
        kv(sb, "name", project.project().name() + "-" + project.project().version());
        comma(sb);
        kv(sb, "documentNamespace", docNamespace);
        comma(sb);

        sb.append("\"creationInfo\":{");
        kv(sb, "created", Instant.ofEpochSecond(DeterministicZip.EPOCH_SECONDS).toString());
        comma(sb);
        sb.append("\"creators\":[\"Tool: jk\"]");
        sb.append('}');
        comma(sb);

        sb.append(packages);
        comma(sb);
        sb.append(relationships);

        sb.append('}');
        sb.append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- helpers ----------------------------------------------------------

    private static void appendSpdxPackage(
            StringBuilder sb,
            String spdxId,
            String group,
            String artifact,
            String version,
            @Nullable String sha256Hex) {
        sb.append('{');
        kv(sb, "SPDXID", spdxId);
        comma(sb);
        kv(sb, "name", group + ":" + artifact);
        comma(sb);
        kv(sb, "versionInfo", version);
        comma(sb);
        kv(sb, "downloadLocation", "NOASSERTION");
        comma(sb);
        sb.append("\"filesAnalyzed\":false");
        comma(sb);
        sb.append("\"externalRefs\":[{");
        kv(sb, "referenceCategory", "PACKAGE-MANAGER");
        comma(sb);
        kv(sb, "referenceType", "purl");
        comma(sb);
        kv(sb, "referenceLocator", "pkg:maven/" + group + "/" + artifact + "@" + version);
        sb.append("}]");
        if (sha256Hex != null) {
            comma(sb);
            sb.append("\"checksums\":[{");
            kv(sb, "algorithm", "SHA256");
            comma(sb);
            kv(sb, "checksumValue", sha256Hex);
            sb.append("}]");
        }
        sb.append('}');
    }

    private static void appendRelationship(StringBuilder sb, String from, String type, String to) {
        sb.append('{');
        kv(sb, "spdxElementId", from);
        comma(sb);
        kv(sb, "relationshipType", type);
        comma(sb);
        kv(sb, "relatedSpdxElement", to);
        sb.append('}');
    }

    private static String sanitizeId(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '.') sb.append(c);
            else sb.append('-');
        }
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String key, String value) {
        appendJsonString(sb, key);
        sb.append(':');
        appendJsonString(sb, value);
    }

    private static void comma(StringBuilder sb) {
        sb.append(',');
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append(Jsonl.quote(s));
    }
}
