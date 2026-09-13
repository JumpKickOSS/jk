// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CycloneDxSbom;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The SBOM sidecars of a publish, from {@link JkBuild} + optional {@link Lockfile} (root-only when
 * no lock): CycloneDX through the writer every application jar embeds ({@link CycloneDxSbom}), so a
 * module's sidecar is the document its jar carries, and SPDX 2.3 over the same runtime rows.
 * Hand-rolled JSON for a lean worker.
 */
public final class Sbom {

    private Sbom() {}

    /** Render the CycloneDX JSON SBOM; deterministic for a given project and lock. */
    public static byte[] cyclonedx(JkBuild project, @Nullable Lockfile lock) {
        return CycloneDxSbom.write(
                project.project().group(),
                project.project().name(),
                project.project().version(),
                lock == null ? List.of() : CycloneDxSbom.components(lock));
    }

    /** Render an SPDX 2.3 JSON SBOM. */
    public static byte[] spdx(JkBuild project, @Nullable Lockfile lock) {
        String docNamespace = "https://buildjk.dev/sbom/"
                + project.project().group()
                + "/"
                + project.project().name()
                + "/"
                + project.project().version()
                + "/"
                + UUID.randomUUID();
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
        kv(sb, "created", Instant.now().toString());
        comma(sb);
        sb.append("\"creators\":[\"Tool: jk\"]");
        sb.append('}');
        comma(sb);

        sb.append("\"documentDescribes\":[\"SPDXRef-Package-Root\"]");
        comma(sb);

        sb.append("\"packages\":[");
        appendSpdxPackage(
                sb,
                "SPDXRef-Package-Root",
                project.project().group(),
                project.project().name(),
                project.project().version(),
                null);
        if (lock != null) {
            int i = 0;
            for (Lockfile.Artifact pkg : ClasspathResolver.artifactsFor(lock, ClasspathResolver.RUNTIME)) {
                sb.append(',');
                appendSpdxPackage(
                        sb,
                        "SPDXRef-Package-" + sanitizeId(pkg.name()) + "-" + i++,
                        pkg.moduleGroup(),
                        pkg.moduleArtifact(),
                        pkg.version(),
                        pkg.checksumHex());
            }
        }
        sb.append(']');

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
