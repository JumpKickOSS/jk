// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.task.FileHashMemo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A module's workspace siblings as the jars and trees its steps read: each sibling's main jar, the
 * first-party worker jars a module's tests fork, and the sibling rule packs staged into its
 * sandbox store.
 */
final class SiblingJars {

    private SiblingJars() {}

    /**
     * Map each workspace sibling to its main output jar, keyed by both project name and {@code
     * group:artifact} coord. Used by {@link #workerJarProps} to locate {@code test-plugin-jars}
     * entries. Empty when this module isn't in a workspace.
     */
    static Map<String, Path> siblingMainJars(Path moduleDir) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        var rootOpt = WorkspaceLocator.findRoot(moduleDir);
        if (rootOpt.isEmpty()) return out;
        Path root = rootOpt.get();
        JkBuild rootManifest = JkBuildParser.parse(ManifestPaths.manifestIn(root));
        if (!rootManifest.isWorkspaceRoot()) return out;
        for (Path dir : WorkspaceModules.memberDirs(root, rootManifest)) {
            Path manifest = ManifestPaths.manifestIn(dir);
            if (!Files.exists(manifest)) continue;
            JkBuild sib = siblingOrNull(manifest);
            if (sib == null) continue;
            BuildLayout layout = BuildLayout.of(dir, sib);
            // An assembly plugin runs from its -all.jar — that's the artifact
            // that bundles plugin-api/PluginMain and the plugin's deps; a
            // plain module ships only its main jar.
            Path jar = sib.assembly() ? layout.assemblyJar() : layout.mainJar();
            String name = sib.project().name();
            out.put(name, jar);
            out.put(sib.project().group() + ":" + name, jar);
            // test-plugin-jars uses short names ("test-runner"); first-party
            // workers publish as jk-<short> — alias so sibling lookup works.
            if (name.startsWith("jk-") && name.length() > 3) {
                out.put(name.substring(3), jar);
            }
            // Also key by the module directory basename (plugins/test-runner → test-runner).
            Path base = dir.getFileName();
            if (base != null) {
                out.putIfAbsent(base.toString(), jar);
            }
        }
        return out;
    }

    static Map<String, String> workerJarProps(Path moduleDir, List<String> modules) throws IOException {
        Map<String, String> props = new LinkedHashMap<>();
        if (modules.isEmpty()) return props;
        Map<String, Path> jarByModule = siblingMainJars(moduleDir);
        for (String module : modules) {
            // Accept short names (test-runner), artifact ids (jk-test-runner), or a group:artifact
            // coordinate. A rule pack listed here is not a worker; it is staged by stageSiblingRulePacks.
            var wj = workerFor(module);
            if (wj.isEmpty()) continue;
            // The worker's own artifact id first: a sibling that merely shares a short name (a rule
            // pack called quarkus beside the jk-quarkus worker) must not answer for it.
            Path jar = jarByModule.get(wj.get().artifactId());
            if (jar == null) jar = jarByModule.get(module);
            if (jar != null) {
                // A sibling: the jar this build makes, before or after it is written, so a key taken
                // either side of the build names the same worker.
                props.put(wj.get().jarProperty(), jar.toAbsolutePath().toString());
            } else {
                // Not a sibling — self-host by reusing the running jk's plugin
                // jar (located via its sha resource + CAS, or a -D override).
                Path located = wj.get().locateStored(JkStores.storeCas());
                if (located != null) props.put(wj.get().jarProperty(), located.toString());
            }
        }
        return props;
    }

    /**
     * Stage into the module's sandbox store every {@code test-plugin-jars} entry that is a workspace
     * sibling but not a worker — a first-party rule pack the module's tests lock scaffolds against.
     * A project's lock finds a first-party pack in the store's {@code jk-local} shelf before it asks
     * any repository, so this is what makes an unpublished pack resolvable under the sandbox home
     * {@code testEnv} points at. Nothing to do for a module outside a workspace.
     */
    static void stageSiblingRulePacks(Path moduleDir, JkBuild project, Map<String, String> testEnv) throws IOException {
        List<String> entries = project.build().testPluginJars();
        if (entries.isEmpty() || !testEnv.containsKey(TestEnv.JK_HOME)) return;
        Map<String, JkBuild> siblings = siblingManifests(moduleDir);
        if (siblings.isEmpty()) return;
        Path store = JkDirs.of(testEnv::get, System.getProperty("user.home")).storeDir();
        for (String entry : entries) {
            if (workerFor(entry).isPresent()) continue;
            JkBuild sibling = siblings.get(entry);
            if (sibling == null) continue;
            var proj = sibling.project();
            Path jar = BuildLayout.of(siblingDir(moduleDir, sibling), sibling).mainJar();
            if (!Files.isRegularFile(jar)) continue;
            Path shelf = store.resolve("repos")
                    .resolve(RepoArtifactResolver.JK_LOCAL)
                    .resolve(proj.group().replace('.', '/'))
                    .resolve(proj.name())
                    .resolve(proj.version());
            Path staged = shelf.resolve(proj.name() + "-" + proj.version() + ".jar");
            // By content: a sibling jar restored under an older mtime is still a new jar.
            if (Files.isRegularFile(staged)
                    && Files.size(staged) == Files.size(jar)
                    && FileHashMemo.contentHash(staged).equals(FileHashMemo.contentHash(jar))) {
                continue;
            }
            Files.createDirectories(shelf);
            Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The first-party worker a {@code test-plugin-jars} entry names: a short name ({@code
     * test-runner}), an artifact id ({@code jk-test-runner}) or a coordinate ({@code
     * cc.jumpkick:jk-test-runner}). Only an unqualified short name is widened with {@code jk-}: a
     * coordinate in another group ({@code cc.jumpkick.guards:quarkus}) is not the worker that
     * happens to share its short name.
     */
    private static Optional<PluginJar> workerFor(String entry) {
        int colon = entry.indexOf(':');
        if (colon >= 0) {
            String group = entry.substring(0, colon);
            String artifact = entry.substring(colon + 1);
            if (!PluginJar.GROUP.equals(group)) return Optional.empty();
            return PluginJar.byArtifactId(artifact);
        }
        var wj = PluginJar.byArtifactId(entry);
        return wj.isPresent() ? wj : PluginJar.byArtifactId("jk-" + entry);
    }

    /** Workspace siblings' manifests, keyed by project name and by {@code group:name}. */
    private static Map<String, JkBuild> siblingManifests(Path moduleDir) throws IOException {
        Map<String, JkBuild> out = new LinkedHashMap<>();
        var rootOpt = WorkspaceLocator.findRoot(moduleDir);
        if (rootOpt.isEmpty()) return out;
        Path root = rootOpt.get();
        JkBuild rootManifest = JkBuildParser.parse(ManifestPaths.manifestIn(root));
        if (!rootManifest.isWorkspaceRoot()) return out;
        for (Path dir : WorkspaceModules.memberDirs(root, rootManifest)) {
            Path manifest = ManifestPaths.manifestIn(dir);
            if (!Files.exists(manifest)) continue;
            JkBuild sib = siblingOrNull(manifest);
            if (sib == null) continue;
            out.put(sib.project().name(), sib);
            out.put(sib.project().group() + ":" + sib.project().name(), sib);
        }
        return out;
    }

    /** The directory a sibling manifest was parsed from, found again by its declared module path. */
    private static Path siblingDir(Path moduleDir, JkBuild sibling) throws IOException {
        Path root = WorkspaceLocator.findRoot(moduleDir).orElseThrow();
        JkBuild rootManifest = JkBuildParser.parse(ManifestPaths.manifestIn(root));
        for (Path dir : WorkspaceModules.memberDirs(root, rootManifest)) {
            Path manifest = ManifestPaths.manifestIn(dir);
            if (!Files.exists(manifest)) continue;
            // an unparsable sibling cannot be the one already parsed
            JkBuild sib = siblingOrNull(manifest);
            if (sib != null
                    && sib.project().group().equals(sibling.project().group())
                    && sib.project().name().equals(sibling.project().name())) return dir;
        }
        throw new IOException("workspace module for " + sibling.project().group() + ":"
                + sibling.project().name() + " is no longer listed at " + root);
    }

    /** A sibling's manifest, or null when it does not parse; a config.toml that does not parse is thrown. */
    static @Nullable JkBuild siblingOrNull(Path manifest) {
        try {
            return JkBuildParser.parse(manifest);
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (IOException | RuntimeException unparsable) {
            return null;
        }
    }
}
