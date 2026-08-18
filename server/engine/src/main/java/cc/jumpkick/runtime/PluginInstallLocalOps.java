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

/** Side-load workspace PluginMain jars into the local store (engine-hosted {@code jk plugin install-local}). */
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
                if (!isPluginWorker(build)) continue;
                String artifactId = build.project().name();
                String version = build.project().version();
                if (version == null || version.isBlank()) version = JkVersion.VERSION;
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
                    lines.add("would install " + artifactId + " " + version + " ← " + source + " (+ " + sideDeps.size()
                            + " classpath jars)");
                    installed++;
                    continue;
                }
                RepoArtifactStore.writeToLocalStore(store, rel, source);
                WorkerClasspath.writeSidecar(dest, sideDeps);
                WorkerClasspath.writeSidecar(source, sideDeps);
                Path libDir = null;
                if (ambientStore) {
                    try {
                        Path workerForLib = Files.isRegularFile(dest) ? dest : source;
                        libDir = WorkerLib.materialize(artifactId, workerForLib, sideDeps);
                        List<Path> libPaths = WorkerLib.pathsIfPresent(artifactId);
                        if (libPaths != null && libPaths.size() > 1) {
                            List<Path> libDeps = new ArrayList<>(libPaths.subList(1, libPaths.size()));
                            WorkerClasspath.writeSidecar(dest, libDeps);
                            WorkerClasspath.writeSidecar(source, libDeps);
                        }
                    } catch (Exception ignored) {
                        // sidecar absolute paths still launch
                    }
                }
                String libNote = libDir != null ? "; lib " + libDir : "";
                lines.add("Installed " + artifactId + " " + version + " → " + dest + " (" + sideDeps.size() + " deps"
                        + libNote + ")");
                installed++;
            }
            int skipped = missing.size();
            if (installed == 0 && skipped == 0) {
                return PluginInstallLocalAck.error(
                        "no PluginMain modules found (need [application] main = PluginMain)");
            }
            return new PluginInstallLocalAck(null, installed, skipped, missing, lines);
        } catch (Exception e) {
            return PluginInstallLocalAck.error(cc.jumpkick.util.Errors.text(e));
        }
    }

    private static boolean isPluginWorker(JkBuild build) {
        String main = build.mainClass();
        return main != null && "cc.jumpkick.plugin.process.PluginMain".equals(main);
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
