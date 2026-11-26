// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.ModuleRuntimeClasspath;
import cc.jumpkick.compile.PackagedTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a module's packagers write over its main classes, for the build and the forecast alike: the
 * plugin-contributed and multi-release trees laid over the classes, their content token, the
 * merged service registrations, and the dependency jars an assembly embeds.
 */
final class PackagedClasses {

    private PackagedClasses() {}

    /**
     * Jars to embed in an assembly / native-image classpath for one module — delegates to {@link
     * ModuleRuntimeClasspath}.
     */
    static List<Path> assemblyDependencyJars(Path moduleDir, JkBuild project, Path lockFile, Path cache)
            throws IOException {
        return ModuleRuntimeClasspath.jars(moduleDir, project, lockFile, JkStores.storeCas());
    }

    /**
     * As above for the forecast: a sibling jar {@code jk clean} took counts as present when the
     * walk pinned the content the build restores, so the set is the one the live step lists.
     */
    static List<Path> assemblyDependencyJars(
            Path moduleDir, JkBuild project, Path lockFile, Path cache, Map<Path, String> restoredJarShas)
            throws IOException {
        return ModuleRuntimeClasspath.jars(
                moduleDir,
                project,
                lockFile,
                new ClasspathResolver(JkStores.storeCas()),
                jar -> Files.exists(jar)
                        || restoredJarShas.containsKey(jar.toAbsolutePath().normalize()));
    }

    /** The module's plugin declarations, as package-jar's key reads them on both sides. */
    static @Nullable PluginDeclarations pluginDeclarationsFor(JkBuild project, BuildLayout layout, Path cache)
            throws IOException, InterruptedException {
        var plugins = ActivePlugins.declared(project, layout.moduleRoot(), cache, layout.moduleTargetDir());
        return plugins == null ? null : plugins.decls();
    }

    /**
     * Every tree packaging merges over the main classes: {@link #existingContributedDirs}, then the
     * {@code [multi-release]} root. The build and the forecast list the same set.
     */
    static List<Path> packagedDirs(@Nullable PluginDeclarations decls, BuildLayout layout, @Nullable JkBuild project) {
        List<Path> out = new ArrayList<>(existingContributedDirs(decls, layout));
        out.addAll(PlannerVersions.packagedRoot(project, layout));
        out.addAll(NodePackaging.packagedRoot(project, layout));
        return out;
    }

    /**
     * The plugin {@code contributesClasses}/{@code contributesResources} dirs that exist, in
     * declaration order; {@link #packagedClasses} lays them over the classes.
     */
    static List<Path> existingContributedDirs(@Nullable PluginDeclarations decls, BuildLayout layout) {
        if (decls == null) return List.of();
        List<Path> out = new ArrayList<>();
        for (Path pth : PluginBuild.contributedDirs(decls, layout)) {
            if (pth != null && Files.isDirectory(pth)) out.add(pth);
        }
        return out;
    }

    /**
     * Cache-key token covering the contributed dirs. Order-sensitive on purpose: contributions
     * merge first-wins, so declaration order is part of what the packaged output depends on.
     */
    static String contributionsToken(List<Path> contributed) throws IOException {
        return contributionsToken(contributed, ClasspathFingerprint.ON_DISK);
    }

    /**
     * As above with each contributed dir's identity read through {@code identity}: the forecast
     * reads a sibling classes dir {@code jk clean} took as the tree the build restores before it
     * packages, so the token is the one the live step computes.
     */
    static String contributionsToken(List<Path> contributed, ClasspathFingerprint.EntryIdentity identity)
            throws IOException {
        if (contributed == null || contributed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Path dir : contributed) {
            sb.append(identity.of(dir)).append('\n');
        }
        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * Main classes plus the plugin-contributed dirs, as the one tree the packagers write: each
     * contribution laid over the classes in order, and every source's service registrations merged.
     * Read where the files lie; nothing is copied. Just the classes when nothing is contributed.
     */
    static PackagedTree packagedClasses(Path classes, List<Path> extra) throws IOException {
        if (extra.isEmpty()) return PackagedTree.of(classes);
        PackagedTree.Builder tree = PackagedTree.builder(classes);
        for (Path contrib : extra) {
            if (Files.isDirectory(contrib)) tree.layer(contrib);
        }
        mergeServiceRegistrations(tree, classes, extra);
        return tree.build();
    }

    /**
     * {@code META-INF/services/*} is the one path a contribution must add to rather than replace:
     * laying the trees over each other lets the last source win, which would drop the providers the
     * classes register. Every source's registrations are kept, the module's own first, each
     * provider once.
     */
    static void mergeServiceRegistrations(PackagedTree.Builder tree, Path classes, List<Path> extra)
            throws IOException {
        Path servicesRel = Path.of("META-INF", "services");
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        List<Path> sources = new ArrayList<>();
        sources.add(classes);
        sources.addAll(extra);
        for (Path source : sources) {
            Path services = source.resolve(servicesRel);
            List<Path> files = new ArrayList<>();
            PathUtil.forEachRegularFile(services, (file, attrs) -> files.add(file));
            files.sort(Comparator.comparing(f -> f.getFileName().toString()));
            for (Path file : files) {
                var providers = merged.computeIfAbsent(file.getFileName().toString(), k -> new LinkedHashSet<>());
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String provider = line.strip();
                    if (!provider.isEmpty() && !provider.startsWith("#")) providers.add(provider);
                }
            }
        }
        for (var e : merged.entrySet()) {
            if (e.getValue().size() < 2) continue; // one source, or one provider: the overlay is right
            tree.put(
                    "META-INF/services/" + e.getKey(),
                    (String.join("\n", e.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
}
