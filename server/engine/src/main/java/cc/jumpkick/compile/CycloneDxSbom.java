// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.jsonl.Jsonl;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Minimal CycloneDX 1.5 SBOM from lockfile coordinates + SHA-256 (deterministic; no serial numbers
 * so reproducible jars stay stable).
 *
 * <p>Components are sorted by {@code group}, {@code artifact}, {@code version}. {@code LockFlow}
 * keeps solver order in memory; {@code LockfileWriter} sorts by name on disk. Without this sort the
 * first {@code jk build} (in-memory lock) and the next {@code jk run} (disk lock) embed different
 * SBOM bytes, miss the package-jar cache, and rebuild native-image.
 */
public final class CycloneDxSbom {

    /** One resolved dependency: exact coordinates + the locked jar SHA-256 (nullable). */
    public record Component(String group, String artifact, String version, String sha256) {

        public Component {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
        }

        String purl() {
            return "pkg:maven/" + group + "/" + artifact + "@" + version;
        }
    }

    private CycloneDxSbom() {}

    /** The CycloneDX JSON document for an application and its resolved runtime components. */
    public static byte[] write(String group, String artifact, String version, List<Component> components) {
        List<Component> ordered = new ArrayList<>(components);
        ordered.sort(Comparator.comparing(Component::group)
                .thenComparing(Component::artifact)
                .thenComparing(Component::version)
                .thenComparing(c -> c.sha256() == null ? "" : c.sha256()));
        StringBuilder sb = new StringBuilder(1024 + ordered.size() * 256);
        sb.append("{\n");
        sb.append("  \"bomFormat\": \"CycloneDX\",\n");
        sb.append("  \"specVersion\": \"1.5\",\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"metadata\": {\n");
        sb.append("    \"component\": {\n");
        sb.append("      \"type\": \"application\",\n");
        sb.append("      \"bom-ref\": ").append(quote("pkg:maven/" + group + "/" + artifact + "@" + version));
        sb.append(",\n");
        sb.append("      \"group\": ").append(quote(group)).append(",\n");
        sb.append("      \"name\": ").append(quote(artifact)).append(",\n");
        sb.append("      \"version\": ").append(quote(version)).append("\n");
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

    private static String quote(String s) {
        return Jsonl.quote(s);
    }
}
