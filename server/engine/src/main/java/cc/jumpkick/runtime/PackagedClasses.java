// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.ModuleRuntimeClasspath;
import cc.jumpkick.compile.PackagedTree;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.plugin.manifest.PluginModule;
import cc.jumpkick.task.ClasspathFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a module's packagers write over its main classes, for the build and the forecast alike: the
 * plugin-contributed and multi-release trees laid over the classes, their content token, the
 * merged service registrations, the sibling classes trees a plugin worker vendors, and the
 * dependency jars an assembly embeds.
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
     * contribution laid over the classes in order, its plugin-templates root left out, the plugin
     * descriptor only ever the module's own, and every source's service registrations merged. Read
     * where the files lie; nothing is copied. Just the classes when nothing is contributed.
     */
    static PackagedTree packagedClasses(Path classes, List<Path> extra) throws IOException {
        if (extra.isEmpty()) return PackagedTree.of(classes);
        PackagedTree.Builder tree = PackagedTree.builder(classes);
        String descriptor = ManifestPaths.PLUGIN_MANIFEST;
        for (Path contrib : extra) {
            if (!Files.isDirectory(contrib)) continue;
            tree.layer(contrib, dir -> isPluginTemplatesRoot(contrib, dir));
            if (!Files.isRegularFile(contrib.resolve(descriptor))) continue;
            Path own = classes.resolve(descriptor);
            if (Files.isRegularFile(own)) tree.put(descriptor, own);
            else tree.remove(descriptor);
        }
        mergeServiceRegistrations(tree, classes, extra);
        return tree.build();
    }

    /**
     * {@code META-INF/services/*} is the one path a contribution must add to rather than replace.
     * Laying the trees over each other lets the last source win, and for a plugin worker whose
     * contribution is a workspace sibling that is itself a plugin — grails over spring-boot — that
     * left the jar registering the sibling's plugin and not its own; the worker then reported "no
     * plugin with protocol prefix" for the very jar it was launched from. Every source's
     * registrations are kept, the module's own first, each provider once.
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

    /** True when {@code dir} is {@code <root>/templates}: the Giter8 trees the registry reads from the owning plugin's jar. */
    static boolean isPluginTemplatesRoot(Path root, Path dir) {
        return root.equals(dir.getParent()) && PLUGIN_TEMPLATES_ROOT.equals(String.valueOf(dir.getFileName()));
    }

    /** Root directory of a plugin jar's Giter8 templates ({@code templates/<lang>/<framework>/<name>.g8/}). */
    static final String PLUGIN_TEMPLATES_ROOT = "templates";

    /**
     * Class dirs of workspace MAIN dependencies to vendor into a plugin-worker jar (plugin-sdk +
     * host). External deps stay on the sidecar POM.
     *
     * <p>The set is the manifests' MAIN closure, whatever is on disk: every sibling in it is one
     * package-jar waits on, so the build and the forecast list the same trees and a missing one
     * hashes as missing rather than dropping out of the packaging key.
     *
     * <p>{@link JkBuildParser#parse(Path)} rewrites {@code workspace:}
     * placeholders to real {@code group:artifact} coordinates before packaging runs, so sibling
     * lookup must accept both forms.
     */
    static List<Path> workerCodecClassDirs(Path moduleDir, JkBuild project) {
        if (moduleDir == null || project == null || !PluginModule.isWorker(moduleDir)) {
            return List.of();
        }
        Path root;
        JkBuild rootManifest;
        try {
            var rootOpt = WorkspaceLocator.findRoot(moduleDir);
            if (rootOpt.isEmpty()) return List.of();
            root = rootOpt.get();
            rootManifest = JkBuildParser.parse(ManifestPaths.manifestIn(root));
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        if (!rootManifest.isWorkspaceRoot()) return List.of();
        Map<String, Path> dirByName = new LinkedHashMap<>();
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<Path, JkBuild> byDir = new LinkedHashMap<>();
        // The build's expansion, outside the fallback: a glob that selects nothing is the build's error.
        for (Path dir : WorkspaceModules.memberDirs(root, rootManifest)) {
            Path manifest = ManifestPaths.manifestIn(dir);
            if (!Files.isRegularFile(manifest)) continue;
            JkBuild sib = SiblingJars.siblingOrNull(manifest);
            if (sib == null) continue;
            byDir.put(dir, sib);
            indexWorkerSibling(dirByName, dirByCoord, dir, sib);
        }
        // Members may depend on the workspace root unit itself.
        byDir.put(root, rootManifest);
        indexWorkerSibling(dirByName, dirByCoord, root, rootManifest);
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        ArrayDeque<JkBuild> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(project);
        while (!q.isEmpty()) {
            JkBuild cur = q.removeFirst();
            for (Dependency d : cur.dependencies().of(Scope.MAIN)) {
                Path dir = workerSiblingDir(d, dirByName, dirByCoord);
                if (dir == null) continue;
                String seenKey = dir.toAbsolutePath().normalize().toString();
                if (!seen.add(seenKey)) continue;
                JkBuild sib = byDir.get(dir);
                if (sib == null) continue;
                out.add(BuildLayout.of(dir, sib).classesDir());
                q.addLast(sib);
            }
        }
        return List.copyOf(out);
    }

    private static void indexWorkerSibling(
            Map<String, Path> dirByName, Map<String, Path> dirByCoord, Path dir, JkBuild sib) {
        String name = sib.project().name();
        dirByName.putIfAbsent(name, dir);
        if (name.startsWith("jk-") && name.length() > 3) {
            dirByName.putIfAbsent(name.substring(3), dir);
        }
        Path base = dir.getFileName();
        if (base != null) dirByName.putIfAbsent(base.toString(), dir);
        dirByCoord.putIfAbsent(sib.project().group() + ":" + name, dir);
    }

    /** Resolve a MAIN dep to a workspace sibling dir (placeholder or rewritten coordinate). */
    private static @Nullable Path workerSiblingDir(
            Dependency d, Map<String, Path> dirByName, Map<String, Path> dirByCoord) {
        String ws = d.workspaceName();
        if (ws != null) {
            String qualified = Dependency.workspaceCoordinate(d.module());
            if (qualified != null) return dirByCoord.get(qualified);
            Path dir = dirByName.get(ws);
            if (dir != null) return dir;
            if (ws.startsWith("jk-") && ws.length() > 3) return dirByName.get(ws.substring(3));
            return dirByName.get("jk-" + ws);
        }
        Path byCoord = dirByCoord.get(d.module());
        if (byCoord != null) return byCoord;
        Path byLib = dirByName.get(d.library());
        if (byLib != null) return byLib;
        return dirByName.get(d.name());
    }
}
