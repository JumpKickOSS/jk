// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.publish;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.pom.PomXml;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Publish-grade {@code pom.xml}: coords, standard scopes, BOM import for PLATFORM; no
 * repositories/build/profiles. PROCESSOR deps are dropped. Lighter than {@code PomExporter}.
 */
public final class PublishablePom {

    public record Pom(String xml) {}

    public record Metadata(
            @Nullable String name,
            @Nullable String description,
            @Nullable String url,
            List<License> licenses,
            List<Developer> developers,
            @Nullable Scm scm) {
        public Metadata {
            licenses = licenses == null ? List.of() : List.copyOf(licenses);
            developers = developers == null ? List.of() : List.copyOf(developers);
        }

        public static Metadata empty() {
            return new Metadata(null, null, null, List.of(), List.of(), null);
        }
    }

    public record License(String name, @Nullable String url) {
        public License {
            Objects.requireNonNull(name, "name");
        }
    }

    public record Developer(
            String id, @Nullable String name, @Nullable String email) {
        public Developer {
            Objects.requireNonNull(id, "id");
        }
    }

    public record Scm(
            @Nullable String url,
            @Nullable String connection,
            @Nullable String developerConnection) {}

    private PublishablePom() {}

    public static Pom render(JkBuild jkBuild, @Nullable Metadata meta) {
        return render(jkBuild, meta, Set.of());
    }

    /**
     * As {@link #render(JkBuild, Metadata)}, with the workspace's sibling coordinates
     * ({@code group:artifact}, see {@code WorkspaceResolve.siblingCoordinates}). A tests-kind edge
     * to a sibling would publish as {@code <type>test-jar</type><classifier>tests</classifier>}
     * against an artifact jk never produces (there is no test-jar packaging task), so those edges
     * are omitted; tests-kind edges to external coordinates are kept — their test-jars exist
     * upstream.
     */
    public static Pom render(JkBuild jkBuild, @Nullable Metadata meta, @Nullable Set<String> workspaceSiblings) {
        return render(jkBuild, meta, workspaceSiblings, Map.of());
    }

    /**
     * As {@link #render(JkBuild, Metadata, Set)}, pinning {@code &lt;version&gt;} from {@code locked}
     * ({@code group:artifact} or package key → exact version) when present so a locally installed
     * POM can rebuild a runtime classpath without re-resolving ranges.
     */
    public static Pom render(
            JkBuild jkBuild,
            @Nullable Metadata meta,
            @Nullable Set<String> workspaceSiblings,
            @Nullable Map<String, String> locked) {
        Objects.requireNonNull(jkBuild, "jkBuild");
        if (meta == null) meta = Metadata.empty();
        if (workspaceSiblings == null) workspaceSiblings = Set.of();
        if (locked == null) locked = Map.of();

        StringBuilder sb = new StringBuilder(512);
        PomXml.appendPreamble(sb);

        Project p = jkBuild.project();
        sb.append("  <groupId>").append(PomXml.escape(p.group())).append("</groupId>\n");
        sb.append("  <artifactId>").append(PomXml.escape(p.name())).append("</artifactId>\n");
        sb.append("  <version>").append(PomXml.escape(p.version())).append("</version>\n");
        sb.append("  <packaging>jar</packaging>\n");

        if (meta.name() != null)
            sb.append("  <name>").append(PomXml.escape(meta.name())).append("</name>\n");
        // Metadata wins over project.description; fall back to the project's
        // description so `jk publish` carries the manifest's description into
        // the POM without forcing every caller to thread it through Metadata.
        String description = meta.description() != null ? meta.description() : p.description();
        if (description != null) {
            sb.append("  <description>").append(PomXml.escape(description)).append("</description>\n");
        }
        if (meta.url() != null)
            sb.append("  <url>").append(PomXml.escape(meta.url())).append("</url>\n");

        appendLicenses(sb, meta.licenses());
        appendDevelopers(sb, meta.developers());
        appendScm(sb, meta.scm());

        Map<String, String> pins = locked;
        PomXml.appendDependencyManagement(sb, jkBuild.dependencies().of(Scope.PLATFORM), d -> versionOf(d, pins));
        appendDependencies(sb, jkBuild, workspaceSiblings, pins);

        sb.append("</project>\n");
        return new Pom(sb.toString());
    }

    private static void appendLicenses(StringBuilder sb, List<License> licenses) {
        if (licenses.isEmpty()) return;
        sb.append("  <licenses>\n");
        for (License l : licenses) {
            sb.append("    <license>\n");
            sb.append("      <name>").append(PomXml.escape(l.name())).append("</name>\n");
            if (l.url() != null) {
                sb.append("      <url>").append(PomXml.escape(l.url())).append("</url>\n");
            }
            sb.append("    </license>\n");
        }
        sb.append("  </licenses>\n");
    }

    private static void appendDevelopers(StringBuilder sb, List<Developer> developers) {
        if (developers.isEmpty()) return;
        sb.append("  <developers>\n");
        for (Developer d : developers) {
            sb.append("    <developer>\n");
            sb.append("      <id>").append(PomXml.escape(d.id())).append("</id>\n");
            if (d.name() != null) {
                sb.append("      <name>").append(PomXml.escape(d.name())).append("</name>\n");
            }
            if (d.email() != null) {
                sb.append("      <email>").append(PomXml.escape(d.email())).append("</email>\n");
            }
            sb.append("    </developer>\n");
        }
        sb.append("  </developers>\n");
    }

    private static void appendScm(StringBuilder sb, @Nullable Scm scm) {
        if (scm == null) return;
        sb.append("  <scm>\n");
        if (scm.url() != null)
            sb.append("    <url>").append(PomXml.escape(scm.url())).append("</url>\n");
        if (scm.connection() != null) {
            sb.append("    <connection>")
                    .append(PomXml.escape(scm.connection()))
                    .append("</connection>\n");
        }
        if (scm.developerConnection() != null) {
            sb.append("    <developerConnection>")
                    .append(PomXml.escape(scm.developerConnection()))
                    .append("</developerConnection>\n");
        }
        sb.append("  </scm>\n");
    }

    private static void appendDependencies(
            StringBuilder sb, JkBuild jkBuild, Set<String> workspaceSiblings, Map<String, String> locked) {
        Scope[] order = {Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST};
        boolean any = false;
        for (Scope s : order) {
            if (!jkBuild.dependencies().of(s).isEmpty()) {
                any = true;
                break;
            }
        }
        if (!any) return;

        sb.append("  <dependencies>\n");
        for (Scope s : order) {
            String mavenScope = PomXml.mavenScope(s);
            for (Dependency d : jkBuild.dependencies().of(s)) {
                // Unresolved workspace placeholders cannot be Maven coordinates.
                if (d.isWorkspace()) continue;
                // A branch-tracked git dep, even though it's locked in jk-lock.toml, is still not a
                // stable reference for external consumers of the published artifact. `jk
                // publish` rejects it up front; skip here as a safety net so a stray caller
                // never emits a broken <version>=branch=...</version>.
                GitRefSpec ref = d.gitSource() == null ? null : d.gitSource().ref();
                if (ref instanceof GitRefSpec.Branch) {
                    continue;
                }
                // A tests-kind sibling edge names the sibling's test-jar — an artifact jk never
                // publishes. Test scope is not transitive, so consumers lose nothing.
                if (d.isTestsKind() && workspaceSiblings.contains(d.module())) {
                    continue;
                }
                PomXml.appendDependency(sb, d, versionOf(d, locked), mavenScope);
            }
        }
        sb.append("  </dependencies>\n");
    }

    private static String versionOf(Dependency d, @Nullable Map<String, String> locked) {
        if (locked != null && !locked.isEmpty()) {
            String pin = locked.get(d.packageKey());
            if (pin == null || pin.isBlank()) pin = locked.get(d.module());
            if (pin != null && !pin.isBlank()) return pin;
        }
        return versionOf(d.version());
    }

    private static String versionOf(VersionSelector v) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Tilde t -> t.version();
            case VersionSelector.Range r -> r.raw();
            case VersionSelector.Latest l -> "LATEST";
            // Maven's own word for "newest, snapshots included". jk's `latest` is really Maven's
            // RELEASE now that it excludes pre-releases, but that mapping is left alone
            // here rather than silently changing bytes in already-published POMs.
            case VersionSelector.Snapshot s -> "LATEST";
        };
    }
}
