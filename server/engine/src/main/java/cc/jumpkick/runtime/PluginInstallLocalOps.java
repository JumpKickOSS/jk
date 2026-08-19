// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.ModuleRuntimeClasspath;
import cc.jumpkick.compile.WorkerClasspath;
import cc.jumpkick.compile.WorkerLib;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.engine.protocol.PluginInstallLocalAck;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Side-load workspace PluginMain jars into the local store ({@code jk install} of a plugin module). */
public final class PluginInstallLocalOps {

    private PluginInstallLocalOps() {}

    public static PluginInstallLocalAck run(
            Path dir, Path cache, Path installRoot, String modulesSpec, boolean dryRun, boolean ambientStore) {
        try {
            Path rootToml = dir.resolve("jk.toml");
            if (!Files.isRegularFile(rootToml)) {
                return PluginInstallLocalAck.error("no jk.toml in " + dir);
            }
            JkBuild root = JkBuildParser.parse(rootToml);
            Map<Path, JkBuild> modules = new LinkedHashMap<>();
            if (root.isWorkspaceRoot()) {
                modules.putAll(WorkspaceLoader.loadModules(dir, root));
            } else {
                modules.put(dir, root);
            }
            if (modulesSpec != null && !modulesSpec.isBlank()) {
                modules = filterModules(dir, modules, modulesSpec);
            }
            Path store = installRoot != null ? installRoot : (ambientStore ? JkDirs.store() : cache);
            int installed = 0;
            List<String> missing = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            for (var e : modules.entrySet()) {
                Path modDir = e.getKey();
                JkBuild build = e.getValue();
                if (!isPluginWorker(modDir)) continue;
                String group = build.project().group();
                String artifactId = build.project().name();
                String version = build.project().version();
                if (version == null || version.isBlank()) version = JkVersion.VERSION;
                if (group == null || group.isBlank()) group = "cc.jumpkick";
                String gav = group + ":" + artifactId + ":" + version;
                BuildLayout layout = BuildLayout.of(modDir, build);
                Path source = preferredWorkerJar(layout);
                String label = dir.relativize(modDir).toString();
                if (source == null || !Files.isRegularFile(source)) {
                    missing.add(label + " (expected " + layout.mainJar().getFileName() + ")");
                    continue;
                }
                String rel = "cc/jumpkick/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
                Path dest = store.resolve("repos/local").resolve(rel);
                List<Path> deps;
                try {
                    deps = ModuleRuntimeClasspath.jars(modDir, build, LockPaths.lockFile(modDir), JkStores.cas(cache));
                } catch (Exception ex) {
                    deps = List.of();
                }
                List<Path> sideDeps = new ArrayList<>();
                Path sourceAbs = source.toAbsolutePath().normalize();
                for (Path d : deps) {
                    if (d == null) continue;
                    Path abs = d.toAbsolutePath().normalize();
                    if (!abs.equals(sourceAbs) && !sideDeps.contains(abs)) sideDeps.add(abs);
                }
                if (sideDeps.isEmpty()) {
                    for (Path p : WorkerClasspath.paths(source)) {
                        Path abs = p.toAbsolutePath().normalize();
                        if (!abs.equals(sourceAbs) && !sideDeps.contains(abs)) sideDeps.add(abs);
                    }
                }
                sideDeps = installSidecarDeps(store, sideDeps);
                if (dryRun) {
                    lines.add(gav);
                    installed++;
                    continue;
                }
                RepoArtifactStore.writeToLocalStore(store, rel, source);
                // Workspace / source sidecar keeps absolute sideDeps (durable across lib
                // rematerialize basename churn from Gradle vs pure-jk installLocal). The store
                // copy may prefer compact lib/ paths below when materialize succeeds.
                WorkerClasspath.writeSidecar(dest, sideDeps);
                WorkerClasspath.writeSidecar(source, sideDeps);
                if (ambientStore) {
                    try {
                        Path workerForLib = Files.isRegularFile(dest) ? dest : source;
                        WorkerLib.materialize(artifactId, workerForLib, sideDeps);
                        List<Path> libPaths = WorkerLib.pathsIfPresent(artifactId);
                        if (libPaths != null && libPaths.size() > 1) {
                            List<Path> libDeps = new ArrayList<>(libPaths.subList(1, libPaths.size()));
                            // Only the store-local jar — not the workspace target jar. Engine
                            // tests launch via -Djk.*.plugin.jar → target/plugins/… and need
                            // sideDeps that still exist after a later Gradle installLocal.
                            WorkerClasspath.writeSidecar(dest, libDeps);
                        }
                    } catch (Exception ignored) {
                        // sidecar absolute paths still launch
                    }
                }
                lines.add(gav);
                installed++;
            }
            int skipped = missing.size();
            if (installed == 0 && skipped == 0) {
                return PluginInstallLocalAck.error(
                        "no plugin worker modules found (need jk-plugin.toml or a Plugin service file)");
            }
            return new PluginInstallLocalAck(null, installed, skipped, missing, lines);
        } catch (Exception e) {
            return PluginInstallLocalAck.error(cc.jumpkick.util.Errors.text(e));
        }
    }

    private static boolean isPluginWorker(Path moduleDir) {
        return cc.jumpkick.plugin.PluginModule.isWorker(moduleDir);
    }

    private static Path preferredWorkerJar(BuildLayout layout) {
        Path main = layout.mainJar();
        if (Files.isRegularFile(main)) return main;
        Path assembly = layout.assemblyJar();
        if (Files.isRegularFile(assembly)) return assembly;
        return null;
    }

    private static List<Path> installSidecarDeps(Path installRoot, List<Path> deps) {
        List<Path> out = new ArrayList<>();
        for (Path d : deps) {
            if (d == null || !Files.isRegularFile(d)) continue;
            String name = d.getFileName().toString();
            if (name.contains("plugin-sdk") && name.endsWith(".jar")) {
                String artifact = name.contains("jk-plugin-sdk") ? "jk-plugin-sdk" : "plugin-sdk";
                String parsed = WorkerLib.jarVersion(name);
                String version = parsed != null ? parsed : JkVersion.VERSION;
                String rel = "cc/jumpkick/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
                try {
                    RepoArtifactStore.writeToLocalStore(installRoot, rel, d);
                    Path stored = installRoot.resolve("repos/local").resolve(rel);
                    if (Files.isRegularFile(stored)) {
                        out.add(stored.toAbsolutePath().normalize());
                        continue;
                    }
                } catch (Exception ignored) {
                    // keep workspace path
                }
            }
            out.add(d.toAbsolutePath().normalize());
        }
        return out;
    }

    private static Map<Path, JkBuild> filterModules(Path workspaceRoot, Map<Path, JkBuild> all, String spec) {
        String[] tokens = spec.split(",");
        Map<Path, JkBuild> out = new LinkedHashMap<>();
        for (var e : all.entrySet()) {
            String rel = workspaceRoot.relativize(e.getKey()).toString().replace('\\', '/');
            String name = e.getValue().project().name();
            for (String t : tokens) {
                String tok = t.trim();
                if (tok.isEmpty()) continue;
                if (rel.contains(tok) || rel.equals(tok) || name.equals(tok) || name.equals("jk-" + tok)) {
                    out.put(e.getKey(), e.getValue());
                    break;
                }
            }
        }
        return out;
    }
}
