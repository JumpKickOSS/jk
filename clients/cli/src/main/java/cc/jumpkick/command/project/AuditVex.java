// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CycloneDxSbom;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The OpenVEX document {@code jk audit --vex} writes: one statement per finding, for the audited
 * project as the product and the vulnerable jar as its subcomponent.
 *
 * <p>Only claims that cannot be wrong are made. A finding is {@code not_affected}, justified {@code
 * component_not_present}, when no lock row of its jar is in a scope that puts it on a runtime
 * classpath (test, processor, dev and plugin jars are never packaged). Every other finding is
 * {@code under_investigation}: whether the jar's vulnerable code runs is not something a call graph
 * can prove, since reflection, {@code ServiceLoader} and auto-configuration reach code no call
 * edge names. A {@code provided} jar is not packaged, but the platform it runs on supplies one.
 *
 * @see <a href="https://github.com/openvex/spec/blob/main/OPENVEX-SPEC.md">OpenVEX specification</a>
 */
final class AuditVex {

    static final String CONTEXT = "https://openvex.dev/ns/v0.2.0";

    /** The scopes whose jars are on some runtime classpath of the product. */
    private static final Set<Scope> PRESENT = presentScopes();

    private AuditVex() {}

    /**
     * The document as JSON.
     *
     * @param id the document's IRI
     * @param product the audited project's purl
     * @param tooling the tool and version that wrote the document
     */
    static String write(
            String id,
            String product,
            String tooling,
            Instant timestamp,
            List<AuditReport.Finding> findings,
            Lockfile lock) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"@context\": ").append(Jsonl.quote(CONTEXT)).append(",\n");
        sb.append("  \"@id\": ").append(Jsonl.quote(id)).append(",\n");
        sb.append("  \"author\": \"jk audit\",\n");
        sb.append("  \"timestamp\": ").append(Jsonl.quote(timestamp.toString())).append(",\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"tooling\": ").append(Jsonl.quote(tooling)).append(",\n");
        sb.append("  \"statements\": [");
        String sep = "\n";
        for (AuditReport.Finding f : findings) {
            sb.append(sep);
            sep = ",\n";
            statement(sb, f, product, present(lock, f.module(), f.version()));
        }
        sb.append(findings.isEmpty() ? "]\n" : "\n  ]\n");
        return sb.append("}\n").toString();
    }

    private static void statement(StringBuilder sb, AuditReport.Finding f, String product, boolean present) {
        int colon = f.module().indexOf(':');
        String jar =
                CycloneDxSbom.purl(f.module().substring(0, colon), f.module().substring(colon + 1), f.version());
        sb.append("    {\n");
        sb.append("      \"vulnerability\": {\"name\": ").append(Jsonl.quote(f.vulnId()));
        if (!f.aliases().isEmpty()) {
            sb.append(", \"aliases\": [");
            for (int i = 0; i < f.aliases().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(Jsonl.quote(f.aliases().get(i)));
            }
            sb.append(']');
        }
        sb.append("},\n");
        sb.append("      \"products\": [{\"@id\": ")
                .append(Jsonl.quote(product))
                .append(", \"subcomponents\": [{\"@id\": ")
                .append(Jsonl.quote(jar))
                .append("}]}],\n");
        if (present) {
            sb.append("      \"status\": \"under_investigation\"\n");
        } else {
            sb.append("      \"status\": \"not_affected\",\n");
            sb.append("      \"justification\": \"component_not_present\",\n");
            sb.append("      \"status_notes\": ")
                    .append(Jsonl.quote(
                            "not on the runtime classpath: " + f.module() + " is a test, build or dev dependency only"))
                    .append('\n');
        }
        sb.append("    }");
    }

    /**
     * True unless every lock row of {@code ga} at {@code version} is in scopes no runtime classpath
     * holds. A finding with no row is present: nothing proves it absent.
     */
    static boolean present(Lockfile lock, String ga, String version) {
        boolean seen = false;
        for (Lockfile.Artifact row : lock.artifacts()) {
            if (!row.version().equals(version)
                    || !PackageId.parse(row.name()).ga().equals(ga)) continue;
            seen = true;
            if (row.inAnyScope(PRESENT)) return true;
        }
        return !seen;
    }

    private static Set<Scope> presentScopes() {
        Set<Scope> scopes = EnumSet.copyOf(ClasspathResolver.RUNTIME);
        scopes.add(Scope.PROVIDED);
        return scopes;
    }
}
