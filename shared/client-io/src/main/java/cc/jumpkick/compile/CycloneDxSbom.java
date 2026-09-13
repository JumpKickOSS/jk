// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The CycloneDX bill of materials jk writes of an artifact: lockfile coordinates plus each locked
 * jar's SHA-256. The one writer behind the SBOM every application jar embeds and the sidecar {@code
 * jk publish --sbom} uploads, so the two name the same spec version and the same components.
 * Deterministic — no serial number, no timestamp — so a reproducible jar stays byte-stable and a
 * module's publish sidecar equals the copy its jar carries.
 *
 * <p>Components are sorted by {@code group}, {@code artifact}, {@code version}. {@code LockFlow}
 * keeps solver order in memory; {@code LockfileWriter} sorts by name on disk. Without this sort the
 * first {@code jk build} (in-memory lock) and the next {@code jk run} (disk lock) embed different
 * SBOM bytes, miss the package-jar cache, and rebuild native-image.
 */
public final class CycloneDxSbom {

    /** The CycloneDX specification version every document names. */
    public static final String SPEC_VERSION = "1.6";

    /** The JSON schema of {@link #SPEC_VERSION}, the document's {@code $schema}. */
    public static final String SCHEMA_URL = "http://cyclonedx.org/schema/bom-" + SPEC_VERSION + ".schema.json";

    /** One resolved dependency: exact coordinates + the locked jar SHA-256 (nullable). */
    public record Component(
            String group,
            String artifact,
            String version,
            @Nullable String sha256) {

        public Component {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
        }

        /** A lock row as a component: its coordinates and the locked jar's SHA-256. */
        public static Component of(Lockfile.Artifact a) {
            return new Component(a.moduleGroup(), a.moduleArtifact(), a.version(), a.checksumHex());
        }

        String purl() {
            return CycloneDxSbom.purl(group, artifact, version);
        }
    }

    private CycloneDxSbom() {}

    /**
     * The components of an artifact's runtime closure: the lock rows on the production RUNTIME
     * classpath, one per module ({@link ClasspathResolver#artifactsFor}). The jar's embedded copy
     * and the publish sidecar are read from these same rows.
     */
    public static List<Component> components(Lockfile lock) {
        List<Component> out = new ArrayList<>();
        for (Lockfile.Artifact a : ClasspathResolver.artifactsFor(lock, ClasspathResolver.RUNTIME)) {
            out.add(Component.of(a));
        }
        return out;
    }

    /**
     * The CycloneDX JSON document for an application and its resolved runtime components. The
     * subject is {@code metadata.component}, identified by its purl; jk names itself under {@code
     * metadata.tools} as the writer.
     */
    public static byte[] write(String group, String artifact, String version, List<Component> components) {
        List<Component> ordered = new ArrayList<>(components);
        ordered.sort(Comparator.comparing(Component::group)
                .thenComparing(Component::artifact)
                .thenComparing(Component::version)
                .thenComparing(c -> c.sha256() == null ? "" : c.sha256()));
        StringBuilder sb = new StringBuilder(1024 + ordered.size() * 256);
        sb.append("{\n");
        sb.append("  \"$schema\": ").append(quote(SCHEMA_URL)).append(",\n");
        sb.append("  \"bomFormat\": \"CycloneDX\",\n");
        sb.append("  \"specVersion\": ").append(quote(SPEC_VERSION)).append(",\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"metadata\": {\n");
        sb.append("    \"tools\": {\n");
        sb.append("      \"components\": [\n");
        sb.append("        {\n");
        sb.append("          \"type\": \"application\",\n");
        sb.append("          \"group\": \"cc.jumpkick\",\n");
        sb.append("          \"name\": \"jk\",\n");
        sb.append("          \"version\": ").append(quote(JkVersion.VERSION)).append("\n");
        sb.append("        }\n");
        sb.append("      ]\n");
        sb.append("    },\n");
        sb.append("    \"component\": {\n");
        sb.append("      \"type\": \"application\",\n");
        sb.append("      \"bom-ref\": ")
                .append(quote(purl(group, artifact, version)))
                .append(",\n");
        sb.append("      \"group\": ").append(quote(group)).append(",\n");
        sb.append("      \"name\": ").append(quote(artifact)).append(",\n");
        sb.append("      \"version\": ").append(quote(version)).append(",\n");
        sb.append("      \"purl\": ")
                .append(quote(purl(group, artifact, version)))
                .append("\n");
        sb.append("    }\n");
        sb.append("  },\n");
        sb.append("  \"components\": [");
        for (int i = 0; i < ordered.size(); i++) {
            Component c = ordered.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\n");
            sb.append("      \"type\": \"library\",\n");
            sb.append("      \"bom-ref\": ").append(quote(c.purl())).append(",\n");
            sb.append("      \"group\": ").append(quote(c.group())).append(",\n");
            sb.append("      \"name\": ").append(quote(c.artifact())).append(",\n");
            sb.append("      \"version\": ").append(quote(c.version())).append(",\n");
            sb.append("      \"purl\": ").append(quote(c.purl()));
            if (c.sha256() != null && !c.sha256().isBlank()) {
                sb.append(",\n      \"hashes\": [{ \"alg\": \"SHA-256\", \"content\": ")
                        .append(quote(c.sha256()))
                        .append(" }]");
            }
            sb.append("\n    }");
        }
        sb.append(ordered.isEmpty() ? "]\n" : "\n  ]\n");
        sb.append("}\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String purl(String group, String artifact, String version) {
        return "pkg:maven/" + group + "/" + artifact + "@" + version;
    }

    private static String quote(String s) {
        return Jsonl.quote(s);
    }
}
