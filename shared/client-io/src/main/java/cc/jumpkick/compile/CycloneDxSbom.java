// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The CycloneDX bill of materials jk writes of an artifact: its module's runtime closure ({@link
 * #closure}), each locked jar with its SHA-256 and what it depends on. The one writer behind the
 * SBOM every application jar embeds and the sidecar {@code jk publish} uploads, so the two name
 * the same spec version and the same components.
 * Deterministic — no serial number, no timestamp — so a reproducible jar stays byte-stable and a
 * module's publish sidecar equals the copy its jar carries.
 *
 * <p>Components are sorted by {@code group}, {@code artifact}, {@code version}, classifier and type. {@code LockFlow}
 * keeps solver order in memory; {@code LockfileWriter} sorts by name on disk. Without this sort the
 * first {@code jk build} (in-memory lock) and the next {@code jk run} (disk lock) embed different
 * SBOM bytes, miss the package-jar cache, and rebuild native-image.
 */
public final class CycloneDxSbom {

    /** The CycloneDX specification version every document names. */
    public static final String SPEC_VERSION = "1.6";

    /** The JSON schema of {@link #SPEC_VERSION}, the document's {@code $schema}. */
    public static final String SCHEMA_URL = "http://cyclonedx.org/schema/bom-" + SPEC_VERSION + ".schema.json";

    /**
     * One resolved dependency: exact coordinates and the locked jar's SHA-256 (nullable).
     *
     * @param classifier the jar's classifier, {@code null} for the main jar
     * @param type the packaging type, {@code jar} by default
     */
    public record Component(
            String group,
            String artifact,
            String version,
            @Nullable String classifier,
            String type,
            @Nullable String sha256) {

        public Component {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(type, "type");
        }

        /** The main jar of {@code group:artifact:version}. */
        public Component(String group, String artifact, String version, @Nullable String sha256) {
            this(group, artifact, version, null, "jar", sha256);
        }

        /** A lock row as a component: its coordinates, classifier and type, and the locked jar's SHA-256. */
        public static Component of(Lockfile.Artifact a) {
            if (!a.isAar() && !PackageId.isMavenPackageKey(a.name())) {
                return new Component(a.moduleGroup(), a.moduleArtifact(), a.version(), a.checksumHex());
            }
            Coordinate c = a.coordinate();
            return new Component(c.group(), c.artifact(), c.version(), c.classifier(), c.type(), a.checksumHex());
        }

        /**
         * The component's package URL, unique per jar: a classifier and a type other than {@code
         * jar} ride as its {@code classifier} and {@code type} qualifiers, as the purl spec's Maven
         * type defines them.
         */
        public String purl() {
            String base = CycloneDxSbom.purl(group, artifact, version);
            List<String> qualifiers = new ArrayList<>(2);
            if (classifier != null && !classifier.isEmpty()) qualifiers.add("classifier=" + encode(classifier));
            if (!type.equals("jar")) qualifiers.add("type=" + encode(type));
            return qualifiers.isEmpty() ? base : base + "?" + String.join("&", qualifiers);
        }
    }

    /**
     * What a module ships, as a bill of materials: the components of its runtime closure, the ones
     * the module itself depends on, and each component's own dependencies, every reference a purl.
     * Canonical — components sorted, edges sorted and deduplicated — so two builds of one closure
     * render the same bytes whichever order the lock listed its rows in.
     *
     * @param direct the purls the module depends on directly
     * @param edges each component's purl to the purls it depends on; a component with no
     *     dependencies maps to an empty list
     */
    public record Closure(List<Component> components, List<String> direct, Map<String, List<String>> edges) {

        /** The closure of a module with no lock: nothing beside the module itself. */
        public static final Closure NONE = new Closure(List.of(), List.of(), Map.of());

        public Closure {
            List<Component> ordered = new ArrayList<>(components);
            ordered.sort(ORDER);
            components = List.copyOf(ordered);
            direct = List.copyOf(new TreeSet<>(direct));
            Map<String, List<String>> sorted = new TreeMap<>();
            for (Component c : components) sorted.put(c.purl(), List.of());
            for (Map.Entry<String, List<String>> e : edges.entrySet()) {
                sorted.put(e.getKey(), List.copyOf(new TreeSet<>(e.getValue())));
            }
            edges = Collections.unmodifiableMap(sorted);
        }
    }

    private static final Comparator<Component> ORDER = Comparator.comparing(Component::group)
            .thenComparing(Component::artifact)
            .thenComparing(Component::version)
            .thenComparing(c -> c.classifier() == null ? "" : c.classifier())
            .thenComparing(Component::type)
            .thenComparing(c -> c.sha256() == null ? "" : c.sha256());

    private CycloneDxSbom() {}

    /**
     * The runtime closure of {@code module}: the rows its production RUNTIME classpath is made of
     * ({@link ClasspathResolver#moduleRows}), so a workspace member names its own dependencies and
     * not its siblings', with the lock's {@code deps} edges between them. POM-only rows (no
     * checksum) ship nothing; an edge through one lands on the rows it stands for.
     *
     * @param lock the lock as the module reads it ({@code MemberRows.view})
     * @param moduleDir the module's directory, which places it in its workspace
     */
    public static Closure closure(Lockfile lock, JkBuild module, Path moduleDir) {
        List<Lockfile.Artifact> rows = ClasspathResolver.moduleRows(lock, ClasspathResolver.RUNTIME, module, moduleDir);
        Map<String, Lockfile.Artifact> byKey = ClasspathResolver.indexArtifacts(rows);
        List<Component> components = new ArrayList<>();
        Map<String, List<String>> edges = new HashMap<>();
        for (Lockfile.Artifact row : rows) {
            if (row.checksum() == null) continue;
            Component c = Component.of(row);
            components.add(c);
            List<String> to = edges.computeIfAbsent(c.purl(), k -> new ArrayList<>());
            for (String dep : row.deps()) shipped(byKey, dep, to, new HashSet<>());
        }
        Set<String> roots =
                new LinkedHashSet<>(ModuleRoots.own(module, moduleDir, ClasspathResolver.RUNTIME, lock.artifacts()));
        roots.addAll(ModuleRoots.inherited(module, moduleDir, ClasspathResolver.RUNTIME, lock.artifacts()));
        List<String> direct = new ArrayList<>();
        for (String root : roots) shipped(byKey, root, direct, new HashSet<>());
        return new Closure(components, direct, edges);
    }

    /** Adds the purls of the shipped rows {@code ref} stands for: its own row, or a POM-only row's edges. */
    private static void shipped(
            Map<String, Lockfile.Artifact> byKey, String ref, List<String> out, Set<Lockfile.Artifact> seen) {
        Lockfile.Artifact row = ClasspathResolver.lookup(byKey, ClasspathResolver.stripVersion(ref));
        if (row == null || !seen.add(row)) return;
        if (row.checksum() != null) {
            out.add(Component.of(row).purl());
            return;
        }
        for (String dep : row.deps()) shipped(byKey, dep, out, seen);
    }

    /**
     * The CycloneDX JSON document for an artifact and its runtime closure. The subject is {@code
     * metadata.component}, identified by its purl; {@code dependencies} lists it and every
     * component with what each depends on. jk names itself under {@code metadata.tools} as the
     * writer, without its own version, so the bytes depend on the artifact and its closure alone
     * and every jk that builds a tree embeds the same document.
     */
    public static byte[] write(String group, String artifact, String version, Closure closure) {
        List<Component> ordered = closure.components();
        String self = purl(group, artifact, version);
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
        sb.append("          \"name\": \"jk\"\n");
        sb.append("        }\n");
        sb.append("      ]\n");
        sb.append("    },\n");
        sb.append("    \"component\": {\n");
        sb.append("      \"type\": \"application\",\n");
        sb.append("      \"bom-ref\": ").append(quote(self)).append(",\n");
        sb.append("      \"group\": ").append(quote(group)).append(",\n");
        sb.append("      \"name\": ").append(quote(artifact)).append(",\n");
        sb.append("      \"version\": ").append(quote(version)).append(",\n");
        sb.append("      \"purl\": ").append(quote(self)).append("\n");
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
        sb.append(ordered.isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"dependencies\": [\n");
        appendDependency(sb, self, closure.direct());
        for (Map.Entry<String, List<String>> e : closure.edges().entrySet()) {
            sb.append(",\n");
            appendDependency(sb, e.getKey(), e.getValue());
        }
        sb.append("\n  ]\n");
        sb.append("}\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendDependency(StringBuilder sb, String ref, List<String> dependsOn) {
        sb.append("    { \"ref\": ").append(quote(ref)).append(", \"dependsOn\": [");
        for (int i = 0; i < dependsOn.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(quote(dependsOn.get(i)));
        }
        sb.append("] }");
    }

    /**
     * The package URL of a Maven coordinate, the reference both SBOM formats and provenance use. The
     * group, artifact and version are percent-encoded as the purl spec asks, so a {@code +} of build
     * metadata reads as itself and not as a space.
     */
    public static String purl(String group, String artifact, String version) {
        return "pkg:maven/" + encode(group) + "/" + encode(artifact) + "@" + encode(version);
    }

    /** A purl component: unreserved characters as they are, every other byte percent-encoded. */
    private static String encode(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || ".-_~".indexOf(c) >= 0) {
                sb.append(c);
            } else {
                sb.append('%').append(Hashing.hex(new byte[] {b}).toUpperCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return Jsonl.quote(s);
    }
}
