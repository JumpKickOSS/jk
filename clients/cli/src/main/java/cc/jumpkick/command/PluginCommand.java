// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.compile.ModuleRuntimeClasspath;
import cc.jumpkick.compile.WorkerClasspath;
import cc.jumpkick.compile.WorkerLib;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jk plugin …} — first-party worker packaging helpers for self-host / dogfood.
 *
 * <p>{@code install-local} side-loads workspace <strong>thin</strong> PluginMain jars into the
 * local Maven layout, writes a {@code .classpath} sidecar of runtime deps (JK-1347), and
 * hard-links worker + deps into {@code $JK_STORE_DIR/lib/&lt;id&gt;/} for compact launch paths
 * (JK-1348).
 */
public final class PluginCommand extends GroupCommand {

    @Override
    public String name() {
        return "plugin";
    }

    @Override
    public String description() {
        return "First-party plugin worker helpers (install-local, uninstall)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new InstallLocalSub(), new UninstallSub());
    }

    /**
     * {@code jk plugin uninstall <artifactId>} — drop a side-loaded worker: its
     * {@code store/lib/<id>/} hardlink dir (unpins CAS inodes for GC — JK-1353) and its
     * {@code repos/local} Maven entries.
     */
    static final class UninstallSub implements CliCommand {

        @Override
        public String name() {
            return "uninstall";
        }

        @Override
        public String description() {
            return "Remove a side-loaded worker's store/lib dir and local repo entries";
        }

        @Override
        public List<cc.jumpkick.model.command.Param> parameters() {
            return List.of(cc.jumpkick.model.command.Param.of(
                    "artifact-id", cc.jumpkick.model.command.Arity.ONE, "worker artifactId, e.g. jk-test-runner"));
        }

        @Override
        public List<Opt> options() {
            return List.of(cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public int run(Invocation in) throws Exception {
            String artifactId = in.positionals().get(0);
            boolean isolated = in.value("cache-dir").isPresent();
            Path installRoot =
                    isolated ? in.value("cache-dir").map(Path::of).orElseThrow() : JkDirs.store();
            boolean removed = false;
            // The shared lib dir belongs to the global store; leave it alone under --cache-dir.
            if (!isolated && Files.isDirectory(WorkerLib.dir(artifactId))) {
                WorkerLib.remove(artifactId);
                removed = true;
            }
            Path repoDir = installRoot.resolve("repos/local/cc/jumpkick").resolve(artifactId);
            if (Files.isDirectory(repoDir)) {
                try (var walk = Files.walk(repoDir)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
                removed = true;
            }
            if (!removed) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Plugin", "nothing installed for " + artifactId));
                return Exit.CONFIG;
            }
            CliOutput.out(cc.jumpkick.cli.tui.CommandWedge.ok("Plugin", "Uninstalled " + artifactId));
            return Exit.SUCCESS;
        }
    }

    /**
     * {@code jk plugin install-local} — copy built PluginMain thin jars + classpath sidecars into
     * the local cache Maven layout used by the engine's worker locator.
     */
    static final class InstallLocalSub implements CliCommand {

        @Override
        public String name() {
            return "install-local";
        }

        @Override
        public String description() {
            return "Side-load workspace plugin jars (+ .classpath) into local repos store";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.value(
                            "<sel>",
                            "Only these modules (comma list / path fragments). Default: all PluginMain workers.",
                            "-m",
                            "--modules"),
                    Opt.flag("Print what would be installed; write nothing.", "--dry-run"),
                    cc.jumpkick.cli.CommonOpts.cacheDir());
        }

        @Override
        public int run(Invocation in) throws Exception {
            GlobalOptions global = GlobalOptions.from(in);
            Path dir = global.workingDir();
            Path rootToml = dir.resolve("jk.toml");
            if (!Files.isRegularFile(rootToml)) {
                CliOutput.err(
                        cc.jumpkick.cli.tui.CommandWedge.fail("Plugin", "no jk.toml in " + PathDisplay.styledRaw(dir)));
                return Exit.CONFIG;
            }

            JkBuild root = JkBuildParser.parse(rootToml);
            Map<Path, JkBuild> modules = new LinkedHashMap<>();
            if (root.isWorkspaceRoot()) {
                modules.putAll(WorkspaceLoader.loadModules(dir, root));
            } else {
                modules.put(dir, root);
            }

            String modulesSpec = in.value("modules").orElse(null);
            if (modulesSpec != null && !modulesSpec.isBlank()) {
                modules = filterModules(dir, modules, modulesSpec);
            }

            Path cache = in.value("cache-dir").map(Path::of).orElse(JkDirs.cache());
            // Ambient installs go to the shared store (PluginJar.locate / CAS). Explicit
            // --cache-dir keeps tests isolated under that root.
            Path installRoot = in.value("cache-dir").isPresent() ? cache : JkDirs.store();
            boolean dryRun = in.isSet("dry-run");
            int installed = 0;
            int skipped = 0;
            List<String> missing = new ArrayList<>();

            // Sidecar classpath comes from the lock — freshen so install-local is never stale.
            int lockCode = cc.jumpkick.cli.EnsureFreshLock.ensure(dir, cache, global, "Plugin");
            if (lockCode != 0) return lockCode;

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
                Path dest = installRoot.resolve("repos/local").resolve(rel);
                List<Path> deps;
                try {
                    deps = ModuleRuntimeClasspath.jars(modDir, build, LockPaths.lockFile(modDir), JkStores.cas(cache));
                } catch (Exception ex) {
                    deps = List.of();
                }
                // Sidecar must not list the worker jar itself (resolve() prepends it).
                // The lock closure is authoritative when non-empty — merging the old sidecar back
                // in would carry removed/upgraded deps forever (JK-1352). Only an empty closure
                // falls back to WorkerClasspath.paths (findPluginSdk) so pure-jk thin jars never
                // ship an empty .classpath (JK-1347).
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
                // Materialize plugin-sdk into the same local store so sidecars don't dangle when
                // workspace target/ is cleaned.
                sideDeps = installSidecarDeps(installRoot, sideDeps);

                if (dryRun) {
                    CliOutput.out("would install " + artifactId + " " + version + " ← " + source + " (+ "
                            + sideDeps.size() + " classpath jars)");
                    installed++;
                    continue;
                }
                RepoArtifactStore.writeToLocalStore(installRoot, rel, source);
                WorkerClasspath.writeSidecar(dest, sideDeps);
                // Also write sidecar next to the build output so -Djk.*.plugin.jar overrides work.
                WorkerClasspath.writeSidecar(source, sideDeps);
                // JK-1348: short store/lib/<id>/ hardlinks for ps-friendly -cp (prefer over
                // sidecar). WorkerLib is rooted in the global store, so an isolated --cache-dir
                // install must not touch it (JK-1354) — sidecar absolute paths still launch.
                Path libDir = null;
                if (!in.value("cache-dir").isPresent()) {
                    try {
                        // Prefer the store copy as the worker hardlink source when ambient install.
                        Path workerForLib = Files.isRegularFile(dest) ? dest : source;
                        libDir = WorkerLib.materialize(artifactId, workerForLib, sideDeps);
                        // Point sidecars at lib paths when materialize succeeded (compact + GC-safe).
                        List<Path> libPaths = WorkerLib.pathsIfPresent(artifactId);
                        if (libPaths != null && libPaths.size() > 1) {
                            List<Path> libDeps = new ArrayList<>(libPaths.subList(1, libPaths.size()));
                            WorkerClasspath.writeSidecar(dest, libDeps);
                            WorkerClasspath.writeSidecar(source, libDeps);
                        }
                    } catch (Exception ignored) {
                        // Best-effort; sidecar absolute paths still launch.
                    }
                }
                String libNote = libDir != null ? "; lib " + PathDisplay.styledRaw(libDir) : "";
                CliOutput.out(cc.jumpkick.cli.tui.CommandWedge.ok(
                        "Plugin",
                        "Installed " + artifactId + " " + version + " → " + PathDisplay.styledRaw(dest) + " ("
                                + sideDeps.size() + " deps" + libNote + ")"));
                installed++;
            }

            if (!missing.isEmpty()) {
                for (String m : missing) {
                    CliOutput.err("  missing jar: " + m + " — run `jk build` first");
                }
                skipped = missing.size();
            }
            if (installed == 0 && skipped == 0) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                        "Plugin", "no PluginMain modules found (need [application] main = PluginMain)"));
                return Exit.CONFIG;
            }
            if (skipped > 0 && installed == 0) return Exit.FAILURE;
            return 0;
        }

        private static boolean isPluginWorker(JkBuild build) {
            String main = build.mainClass();
            return main != null && "cc.jumpkick.plugin.process.PluginMain".equals(main);
        }

        /** Prefer thin main jar; fall back to legacy assembly jar if present. */
        private static Path preferredWorkerJar(BuildLayout layout) {
            Path main = layout.mainJar();
            if (Files.isRegularFile(main)) return main;
            Path assembly = layout.assemblyJar();
            if (Files.isRegularFile(assembly)) return assembly;
            return null;
        }

        /**
         * For workspace {@code plugin-sdk} jars, also copy into {@code repos/local} and rewrite the
         * sidecar path to the store copy so workers keep launching after {@code target/} is cleaned.
         */
        private static List<Path> installSidecarDeps(Path installRoot, List<Path> deps) {
            List<Path> out = new ArrayList<>();
            for (Path d : deps) {
                if (d == null || !Files.isRegularFile(d)) continue;
                String name = d.getFileName().toString();
                if (name.contains("plugin-sdk") && name.endsWith(".jar")) {
                    // jk-plugin-sdk-0.10.1.jar, plugin-sdk-0.1.0-SNAPSHOT.jar — the shared parser
                    // (WorkerLib.jarVersion) keeps m2 placement aligned with lib-id derivation.
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
                        /* keep workspace path */
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
}
