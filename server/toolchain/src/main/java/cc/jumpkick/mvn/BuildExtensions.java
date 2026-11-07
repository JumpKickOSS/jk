// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.maven.model.Build;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * What a POM's {@code <build><extensions>} — and the lifecycle plugins that play the same role
 * from {@code <build><plugins>} — mean to the import. os-maven-plugin exists to export the host's
 * {@code os.detected.*} properties, which the effective model values from the running host already
 * ({@link cc.jumpkick.repo.HostClassifiers}), so it is satisfied with nothing written. An extension
 * whose effect is on how Maven runs — a CI reporter, a deploy transport, a flattened POM, a build
 * cache — is a Tier-2 row naming what it does and what jk has in its place; one that is a packaging
 * jk does not build — an archetype, Tycho, an OSGi bundle — is a Tier-3 row saying so; an extension
 * the import knows nothing about is a Tier-3 row naming its coordinate.
 */
final class BuildExtensions {

    /** Extensions whose whole effect is a set of properties the effective model values itself. */
    static final Set<String> PROPERTY_ONLY = Set.of("kr.motd.maven:os-maven-plugin");

    /**
     * What an extension does under Maven, at the severity of its row: {@code WARNING} when jk's
     * build has the effect on its own terms, {@code ERROR} when it shapes an artifact jk does not
     * build.
     */
    private record Role(String effect, ImportReport.Severity severity) {}

    private static Map.Entry<String, Role> covered(String ga, String effect) {
        return Map.entry(ga, new Role(effect, ImportReport.Severity.WARNING));
    }

    private static Map.Entry<String, Role> unbuilt(String ga, String effect) {
        return Map.entry(ga, new Role(effect, ImportReport.Severity.ERROR));
    }

    private static final String DEPLOY_TRANSPORT = "deploy transport; jk publish speaks HTTP(S), s3:// and gs://";

    /** The common extensions and lifecycle plugins, by {@code groupId:artifactId}. */
    private static final Map<String, Role> ROLES = Map.ofEntries(
            covered("io.quarkus.bot:build-reporter-maven-extension", "reports to CI; outputs do not depend on it"),
            covered(
                    "io.github.gitflow-incremental-builder:gitflow-incremental-builder",
                    "builds changed modules only; jk build does that from its cache"),
            covered("org.apache.maven.wagon:wagon-ssh", "ssh " + DEPLOY_TRANSPORT),
            covered("org.apache.maven.wagon:wagon-ssh-external", "external-ssh " + DEPLOY_TRANSPORT),
            covered("org.apache.maven.wagon:wagon-webdav-jackrabbit", "WebDAV " + DEPLOY_TRANSPORT),
            covered("org.apache.maven.wagon:wagon-ftp", "ftp " + DEPLOY_TRANSPORT),
            covered(
                    "org.codehaus.mojo:flatten-maven-plugin",
                    "flattens the deployed POM; jk publish writes a flat one"),
            covered(
                    "org.apache.maven.extensions:maven-build-cache-extension",
                    "caches module outputs; jk's action cache does this"),
            covered("io.takari.maven:takari-smart-builder", "schedules the reactor; jk build schedules its own graph"),
            covered(
                    "fr.brouillard.oss:jgitver-maven-plugin",
                    "versions from git tags; set version in jk.toml from the tag before jk build"),
            unbuilt(
                    "org.apache.maven.archetype:archetype-packaging",
                    "maven-archetype packaging, which jk does not build"),
            unbuilt("org.eclipse.tycho:tycho-maven-plugin", "Tycho/p2 packaging, which jk does not build"),
            unbuilt("org.apache.felix:maven-bundle-plugin", "OSGi bundle packaging; jk writes no OSGi manifest"),
            unbuilt("com.github.maven-nar:nar-maven-plugin", "nar native packaging, which jk does not build"));

    private BuildExtensions() {}

    /**
     * The artifactIds of the module's {@code <build><plugins>} entries that are lifecycle
     * extensions with a known role: their row comes from {@link #report}, not the generic plugin
     * row.
     */
    static Set<String> extensionPlugins(Model model) {
        Set<String> out = new LinkedHashSet<>();
        for (Plugin plugin : PluginFacts.plugins(model)) {
            if (ROLES.containsKey(ga(plugin)) && !PluginFacts.MAPPED_PLUGINS.contains(plugin.getArtifactId())) {
                out.add(plugin.getArtifactId());
            }
        }
        return out;
    }

    /**
     * One row per extension the import writes nothing for; a property-only extension needs no row.
     * In a workspace ({@code inherited} set) an extension the POM inherits is counted onto the
     * declaring POM's row instead of said at every module; the root's own go there too.
     */
    static void report(
            EffectiveModel em, ImportReport.Builder report, @Nullable InheritedRows inherited, boolean isRoot) {
        Build build = em.model().getBuild();
        if (build == null) return;
        for (Extension extension : build.getExtensions()) {
            String ga = extension.getGroupId() + ":" + extension.getArtifactId();
            if (PROPERTY_ONLY.contains(ga)) continue;
            Role role = ROLES.get(ga);
            String effect = role != null ? role.effect() : "Maven core extension, which jk does not load";
            ImportReport.Severity severity = role != null ? role.severity() : ImportReport.Severity.ERROR;
            String message = "extension " + coordinate(ga, extension.getVersion()) + ": " + effect + remedy(severity);
            row(em, report, inherited, isRoot, severity, message, raw -> declaresExtension(raw, ga));
        }
        for (Plugin plugin : PluginFacts.plugins(em.model())) {
            String ga = ga(plugin);
            Role role = ROLES.get(ga);
            if (role == null || PluginFacts.MAPPED_PLUGINS.contains(plugin.getArtifactId())) continue;
            String message =
                    "plugin " + coordinate(ga, plugin.getVersion()) + ": " + role.effect() + remedy(role.severity());
            row(
                    em,
                    report,
                    inherited,
                    isRoot,
                    role.severity(),
                    message,
                    raw -> InheritedRows.declaresPlugin(raw, plugin.getArtifactId()));
        }
    }

    /** A packaging jk does not build keeps its Maven step; an effect jk has needs nothing kept. */
    private static String remedy(ImportReport.Severity severity) {
        return severity == ImportReport.Severity.ERROR
                ? ". Skipped; drop it or run that step with jk mvn"
                : "; skipped";
    }

    /** The row itself, or its count onto the declaring POM's row in a workspace. */
    private static void row(
            EffectiveModel em,
            ImportReport.Builder report,
            @Nullable InheritedRows inherited,
            boolean isRoot,
            ImportReport.Severity severity,
            String message,
            Predicate<Model> declares) {
        if (inherited == null || (!isRoot && declares.test(em.raw()))) {
            if (severity == ImportReport.Severity.ERROR) report.error(message);
            else report.warning(message);
        } else if (isRoot) {
            inherited.declaredByRoot(severity, message);
        } else {
            inherited.inherited(inherited.declaredBy(em, declares), severity, message);
        }
    }

    private static String ga(Plugin plugin) {
        return plugin.getGroupId() + ":" + plugin.getArtifactId();
    }

    /** Whether a raw model lists the extension {@code ga} itself. */
    private static boolean declaresExtension(Model raw, String ga) {
        Build build = raw.getBuild();
        return build != null
                && build.getExtensions().stream().anyMatch(e -> ga.equals(e.getGroupId() + ":" + e.getArtifactId()));
    }

    /** {@code g:a:v}, or {@code g:a} when the version is a property no POM in the chain defines. */
    private static String coordinate(String ga, @Nullable String version) {
        @Nullable String usable = PluginFacts.usable(version);
        return usable == null ? ga : ga + ":" + usable;
    }
}
