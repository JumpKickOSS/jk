// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.compile.ModuleRuntimeClasspath;
import cc.jumpkick.compile.WorkerClasspath;
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
 * local Maven layout and writes a {@code .classpath} sidecar of runtime deps (JK-1347) so the
 * engine can {@code java -cp worker:deps… PluginMain}.
 */
public final class PluginCommand extends GroupCommand {

    @Override
    public String name() {
        return "plugin";
    }

    @Override
    public String description() {
        return "First-party plugin worker helpers (install-local)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new InstallLocalSub());
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
            // Plugin jars live under the store (same root PluginJar.locate / CAS use), not a
            // transient JK_CACHE_DIR (JK-1347).
            Path store = JkStores.storeRootFor(cache);
            boolean dryRun = in.isSet("dry-run");
            int installed = 0;
            int skipped = 0;
            List<String> missing = new ArrayList<>();

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
                    deps = ModuleRuntimeClasspath.jars(
                            modDir, build, LockPaths.lockFile(modDir), JkStores.cas(cache));
                } catch (Exception ex) {
                    deps = List.of();
                }
                // Sidecar must not list the worker jar itself (resolve() prepends it).
                List<Path> sideDeps = new ArrayList<>();
                Path sourceAbs = source.toAbsolutePath().normalize();
                for (Path d : deps) {
                    if (d == null) continue;
                    Path abs = d.toAbsolutePath().normalize();
                    if (!abs.equals(sourceAbs)) sideDeps.add(abs);
                }

                if (dryRun) {
                    CliOutput.out("would install " + artifactId + " " + version + " ← " + source
                            + " (+ " + sideDeps.size() + " classpath jars)");
                    installed++;
                    continue;
                }
                RepoArtifactStore.writeToLocalStore(store, rel, source);
                WorkerClasspath.writeSidecar(dest, sideDeps);
                // Also write sidecar next to the build output so -Djk.*.plugin.jar overrides work.
                WorkerClasspath.writeSidecar(source, sideDeps);
                CliOutput.out(cc.jumpkick.cli.tui.CommandWedge.ok(
                        "Plugin",
                        "Installed " + artifactId + " " + version + " → " + PathDisplay.styledRaw(dest)
                                + " (" + sideDeps.size() + " deps)"));
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
