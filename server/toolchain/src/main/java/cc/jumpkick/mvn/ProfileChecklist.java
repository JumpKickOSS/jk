// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Activation;
import org.apache.maven.model.ActivationOS;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.jspecify.annotations.Nullable;

/**
 * Per-profile import diagnostics. A profile Maven activated on this machine (active by default, or
 * by JDK / OS / property / file) is already folded into the effective model, so the report says so;
 * every other profile becomes a checklist of what to port by hand, because jk's profile model
 * carries only javac / JVM arguments.
 */
final class ProfileChecklist {

    private ProfileChecklist() {}

    static void report(Profile profile, boolean active, ImportReport.Builder report) {
        String id = profile.getId();
        String label = id == null || id.isBlank() ? "<unnamed>" : id;
        List<String> parts = new ArrayList<>();
        String activation = describeActivation(profile.getActivation());
        if (activation != null) parts.add(activation);
        parts.addAll(active ? appliedPayload(profile) : portablePayload(profile, label));
        if (parts.isEmpty()) {
            parts.add(active ? "contained no payload" : "contained no convertible payload; dropped");
        }
        String verdict = active ? "` was active on this machine and is folded into the import: " : "`: ";
        report.warning("Maven profile `" + label + verdict + String.join("; ", parts) + ".");
    }

    private static List<String> appliedPayload(Profile profile) {
        List<String> parts = new ArrayList<>();
        int deps = profile.getDependencies().size();
        if (deps > 0) parts.add(deps + " dependenc" + (deps == 1 ? "y" : "ies"));
        int managed = managedCount(profile.getDependencyManagement());
        if (managed > 0) parts.add(managed + " dependencyManagement entr" + (managed == 1 ? "y" : "ies"));
        if (!profile.getProperties().isEmpty()) {
            parts.add("properties=[" + String.join(",", profile.getProperties().stringPropertyNames()) + "]");
        }
        List<String> plugins = pluginIds(profile.getBuild());
        if (!plugins.isEmpty())
            parts.add("plugins=[" + String.join(",", plugins) + "] (plugin mapping is not yet implemented)");
        return parts;
    }

    private static List<String> portablePayload(Profile profile, String label) {
        List<String> parts = new ArrayList<>();
        int deps = profile.getDependencies().size();
        if (deps > 0) {
            parts.add(deps
                    + " dependenc"
                    + (deps == 1 ? "y" : "ies")
                    + " (convert to a jk feature `"
                    + label
                    + "` if opt-in, or move into the main deps list)");
        }
        int managed = managedCount(profile.getDependencyManagement());
        if (managed > 0) {
            parts.add(managed
                    + " dependencyManagement entr"
                    + (managed == 1 ? "y" : "ies")
                    + " (inline versions on the matching `<dependency>` or use a BOM import)");
        }
        if (!profile.getProperties().isEmpty()) {
            parts.add("properties=["
                    + String.join(",", profile.getProperties().stringPropertyNames())
                    + "]"
                    + " (no jk equivalent — fold maven.compiler.* into project.jdk; drop the rest)");
        }
        List<String> plugins = pluginIds(profile.getBuild());
        if (!plugins.isEmpty()) {
            parts.add("plugins=[" + String.join(",", plugins) + "] (plugin mapping is not yet implemented)");
        }
        if (!profile.getRepositories().isEmpty()) {
            parts.add("repositories declared (move into the top-level `repositories` block)");
        }
        return parts;
    }

    private static int managedCount(@Nullable DependencyManagement dm) {
        return dm == null ? 0 : dm.getDependencies().size();
    }

    private static List<String> pluginIds(@Nullable BuildBase build) {
        List<String> ids = new ArrayList<>();
        if (build == null) return ids;
        for (Plugin plugin : build.getPlugins()) {
            String artifactId = plugin.getArtifactId();
            if (artifactId != null && !artifactId.isBlank()) ids.add(artifactId);
        }
        return ids;
    }

    private static @Nullable String describeActivation(@Nullable Activation activation) {
        if (activation == null) return null;
        List<String> kinds = new ArrayList<>();
        if (activation.isActiveByDefault()) kinds.add("activeByDefault");
        String jdk = activation.getJdk();
        if (jdk != null && !jdk.isBlank()) kinds.add("jdk=" + jdk);
        ActivationOS os = activation.getOs();
        if (os != null) {
            String family = os.getFamily();
            String name = os.getName();
            kinds.add("os=" + (family != null ? family : name != null ? name : "?")
                    + " (use jk target predicates per dep)");
        }
        ActivationProperty property = activation.getProperty();
        if (property != null) {
            String name = property.getName();
            kinds.add("property="
                    + (name != null ? name : "?")
                    + " (no jk equivalent — replace with an explicit jk profile or feature)");
        }
        if (activation.getFile() != null) {
            kinds.add("file-existence (jk has no equivalent — refactor to a jk profile)");
        }
        return kinds.isEmpty() ? null : "activation=" + String.join("+", kinds);
    }
}
