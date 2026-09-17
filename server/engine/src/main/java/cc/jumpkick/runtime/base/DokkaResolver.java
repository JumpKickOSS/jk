// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.VersionSelectors;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Dokka as a pinned, CAS-cached tool: the {@code dokka-cli} fat jar plus the plugin closure the
 * chosen output format needs — {@code javadoc-plugin} (which brings {@code dokka-base} and {@code
 * kotlin-as-java-plugin}) or {@code dokka-base} alone for HTML — and the Kotlin analysis plugin
 * both formats read sources through. Resolved through {@link ToolClosure}, so a version is fetched
 * once and a warm build pays no resolve.
 */
public final class DokkaResolver {

    /** The Dokka release a module gets when {@code [dokka] version} is absent. */
    public static final String DEFAULT_VERSION = "2.2.0";

    private static final String GROUP = "org.jetbrains.dokka";
    /** The {@code dokka-cli} module a {@code [dokka] version} pins. */
    public static final String CLI = GROUP + ":dokka-cli";

    private static final String ANALYSIS = GROUP + ":analysis-kotlin-symbols";
    private static final String JAVADOC_PLUGIN = GROUP + ":javadoc-plugin";
    private static final String BASE_PLUGIN = GROUP + ":dokka-base";

    /** The launcher jar and the {@code -pluginsClasspath} for one Dokka version and output format. */
    public record Tool(String version, Path cli, List<Path> plugins) {
        public Tool {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(cli, "cli");
            plugins = List.copyOf(plugins);
        }
    }

    private DokkaResolver() {}

    /**
     * The exact Dokka release {@code project} documents with: the manifest's selector when it is a
     * pin, else the highest stable release the repositories advertise that matches it.
     */
    public static String version(JkBuild project, RepoGroup repos) throws IOException, InterruptedException {
        VersionSelector selector = project.build().dokka().version();
        if (selector instanceof VersionSelector.Exact exact) return exact.version();
        List<String> available = repos.availableVersions(Coordinate.ofModule(CLI, "any"));
        VersionSet set = VersionSelectors.toVersionSet(selector);
        List<String> matching = available.stream().filter(set::contains).toList();
        if (selector instanceof VersionSelector.Latest) {
            List<String> stable = matching.stream().filter(Versions::isStable).toList();
            if (!stable.isEmpty()) matching = stable;
        }
        return matching.stream()
                .max(Versions::compare)
                .orElseThrow(() -> new MavenRepo.ArtifactNotFoundException("no Dokka release matches [dokka] version "
                        + selector.raw() + " (available: " + String.join(", ", available) + ")"));
    }

    /** Fetch the CLI and the plugin closure for {@code version} and {@code format} into the CAS. */
    public static Tool resolve(RepoGroup repos, Cas cas, String version, JkBuild.Dokka.Format format)
            throws IOException, InterruptedException {
        Path cli = ToolClosure.single(repos, Coordinate.ofModule(CLI, version));
        LinkedHashSet<Path> plugins = new LinkedHashSet<>();
        String entry = format == JkBuild.Dokka.Format.HTML ? BASE_PLUGIN : JAVADOC_PLUGIN;
        plugins.addAll(ToolClosure.resolve(repos, cas, "dokka-" + format.wireName(), "Dokka", entry, version));
        plugins.addAll(ToolClosure.resolve(repos, cas, "dokka-analysis", "Dokka analysis", ANALYSIS, version));
        return new Tool(version, cli, new ArrayList<>(plugins));
    }
}
