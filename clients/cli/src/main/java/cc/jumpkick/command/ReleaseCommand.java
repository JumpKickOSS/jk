// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.engine.protocol.ProjectInfo;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk release} (alias {@code jk dist}) — build, side-load workers, and assemble a local
 * distribution directory suitable for {@code ./install.sh <out>/jk} / {@code jk self materialize}.
 *
 * <p>Not {@code jk publish} (remote upload) and not {@code jk install} (user app install).
 *
 * <p>JumpKick ship shape is fixed: a <strong>native CLI</strong> plus a <strong>JVM engine</strong>
 * assembly jar (and PluginMain workers side-loaded into the local cache). There is no
 * {@code --native}/{@code --jvm} product mode — the client is always the native binary when one is
 * available (build it with {@code jk native}); otherwise the currently running {@code jk} is staged
 * as a bootstrap client so the engine jar can still be dogfooded.
 *
 * <pre>
 *   &lt;out&gt;/
 *     jk                         # native CLI (preferred) or bootstrap client
 *     lib/
 *       jk-engine-&lt;ver&gt;.jar      # JVM engine assembly (version must match client)
 * </pre>
 */
public final class ReleaseCommand implements CliCommand {

    @Override
    public String name() {
        return "release";
    }

    @Override
    public List<String> aliases() {
        return List.of("dist");
    }

    @Override
    public String description() {
        return "Assemble a local dist (CLI + engine + workers)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Output dir (default: target/dist)", "--out"),
                Opt.flag("Skip tests during the build step.", "--skip-tests"),
                Opt.flag("Skip jk native when no native CLI exists", "--skip-native"),
                Opt.flag("Print the plan; build nothing and write nothing.", "--dry-run"),
                Opt.value("<sel>", "Only these modules (default: whole workspace)", "-m", "--modules"),
                cc.jumpkick.cli.CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path rootToml = dir.resolve("jk.toml");
        if (!Files.isRegularFile(rootToml)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Release", "no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }

        boolean dryRun = in.isSet("dry-run");
        boolean skipTests = in.isSet("skip-tests");
        boolean skipNative = in.isSet("skip-native");
        Path out = in.value("out").map(Path::of).orElse(dir.resolve("target").resolve("dist"));
        if (!out.isAbsolute()) out = dir.resolve(out).normalize();
        String modulesSpec = in.value("modules").orElse(null);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);

        ProjectInfo root = BuildCommand.projectInfoOrNull(dir);
        if (root == null) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Release", "could not read project summary at " + dir);
            return Exit.CONFIG;
        }
        Path engineDir = findEngineModule(dir, root);
        Path cliDir = findCliModule(dir, root);

        if (dryRun) {
            Path nativeBin = cliDir != null ? findNativeClient(cliDir) : null;
            CliOutput.out("jk release plan:");
            CliOutput.out("  working dir: " + dir);
            CliOutput.out("  out:         " + out);
            CliOutput.out("  skip-tests:  " + skipTests);
            CliOutput.out("  skip-native: " + skipNative);
            CliOutput.out("  client:      native CLI preferred"
                    + (nativeBin != null
                            ? " (found " + nativeBin + ")"
                            : skipNative
                                    ? " (none yet; will stage running jk)"
                                    : " (will try `jk native` if eligible, else running jk)"));
            CliOutput.out("  engine:      JVM assembly" + (engineDir != null ? " from " + engineDir : " (none found)"));
            CliOutput.out("  cli:         " + (cliDir != null ? cliDir : "(none found)"));
            CliOutput.out("  then:        jk plugin install-local");
            return 0;
        }

        // 1) Build JVM modules (engine assembly, plugins, libraries).
        // When --skip-native, drop every [native] always module from the default workspace
        // build — those modules demand native-image on every `jk build`, which is exactly what
        // skip-native is opting out of. Explicit -m still wins. An all-native workspace skips
        // this step outright rather than falling back to the full build it opted out of.
        String buildModules = modulesSpec;
        boolean skipBuildStep = false;
        if (skipNative && (buildModules == null || buildModules.isBlank()) && root.workspaceRoot()) {
            List<String> keep = modulesWithoutNativeAlways(dir, root);
            if (keep.size() < root.moduleDirs().size()) {
                if (keep.isEmpty()) {
                    cc.jumpkick.cli.tui.CommandWedge.printOk(
                            "Release", "every module is [native] always — skipping the JVM build step");
                    skipBuildStep = true;
                } else {
                    buildModules = String.join(",", keep);
                }
            }
        }
        if (!skipBuildStep) {
            int buildCode = runBuild(dir, skipTests, buildModules, cacheDir);
            if (buildCode != 0) return buildCode;
        }
        int code;

        // 2) Ensure a native CLI when the module is native-eligible and none is staged yet
        if (!skipNative && cliDir != null && findNativeClient(cliDir) == null && isNativeEligible(cliDir)) {
            cc.jumpkick.cli.tui.CommandWedge.printOk("Release", "no native CLI yet — running `jk native --skip-tests`");
            code = runNative(dir, cacheDir);
            if (code != 0) {
                cc.jumpkick.cli.tui.CommandWedge.printFail(
                        "Release",
                        "`jk native` failed — re-run with --skip-native to stage the running client, "
                                + "or fix GraalVM / native-image and retry");
                return code;
            }
        }

        // 3) Side-load PluginMain workers
        code = runInstallLocal(dir, cacheDir);
        if (code != 0) return code;

        // 4) Assemble ship layout
        Files.createDirectories(out.resolve("lib"));
        String version = JkVersion.VERSION;

        Path engineJar = findEngineAssembly(engineDir);
        if (engineJar == null) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Release",
                    "no engine assembly jar found — need server/engine with assembly = true and a successful build");
            return Exit.FAILURE;
        }
        Path stagedEngine = out.resolve("lib").resolve("jk-engine-" + version + ".jar");
        Files.copy(engineJar, stagedEngine, StandardCopyOption.REPLACE_EXISTING);
        cc.jumpkick.cli.tui.CommandWedge.printOk("Release", "engine (JVM) → " + PathDisplay.styledRaw(stagedEngine));

        Path clientBin = resolveClientBinary(cliDir);
        if (clientBin == null || !Files.isRegularFile(clientBin)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Release",
                    "no client binary — run `jk native` (clients/cli has [native] enabled = \"always\"), "
                            + "or ensure `jk` is on PATH for a bootstrap client");
            return Exit.FAILURE;
        }
        Path stagedClient = out.resolve("jk");
        Files.copy(clientBin, stagedClient, StandardCopyOption.REPLACE_EXISTING);
        try {
            stagedClient.toFile().setExecutable(true);
        } catch (Exception ignored) {
            // Windows / non-POSIX: install.sh / materialize may still work
        }
        boolean nativeClient = isNativeClientPath(cliDir, clientBin);
        cc.jumpkick.cli.tui.CommandWedge.printOk(
                "Release",
                (nativeClient ? "client (native)" : "client (bootstrap)")
                        + " → "
                        + PathDisplay.styledRaw(stagedClient)
                        + " (from "
                        + clientBin
                        + ")");
        if (!nativeClient) {
            CliOutput.out("  tip: run `jk native --skip-tests` then `jk release` again for a production native CLI");
        }

        // Promote Class-C ship artifacts (native CLI, engine fat jar) into the long-lived store CAS
        // so aggressive action-cache eviction does not drop a just-released binary.
        promoteReleasedArtifacts(cacheDir, clientBin, engineJar, nativeClient);

        CliOutput.out("");
        cc.jumpkick.cli.tui.CommandWedge.printOk("Release", "distribution ready at " + PathDisplay.styledRaw(out));
        CliOutput.out("  next: ./install.sh " + out.resolve("jk"));
        CliOutput.out("    or: jk self materialize " + out.resolve("jk") + " " + stagedEngine);
        return 0;
    }

    /**
     * Best-effort: move released fat/native bytes from the action cache CAS into the artifact
     * store (hard-link when possible). Failures never fail the release.
     */
    private static void promoteReleasedArtifacts(Path cacheDir, Path clientBin, Path engineJar, boolean nativeClient) {
        try {
            Path cacheRoot = cacheDir != null ? cacheDir : cc.jumpkick.util.JkDirs.cache();
            var cacheCas = cc.jumpkick.cache.JkStores.cacheCas(cacheRoot);
            var storeCas = cc.jumpkick.cache.JkStores.storeCas();
            java.util.List<Path> files = new java.util.ArrayList<>();
            if (engineJar != null && Files.isRegularFile(engineJar)) files.add(engineJar);
            if (nativeClient && clientBin != null && Files.isRegularFile(clientBin)) files.add(clientBin);
            if (files.isEmpty()) return;
            var report = cc.jumpkick.cache.ActionPromote.promoteFiles(cacheCas, storeCas, files);
            if (report.promoted() > 0 || report.alreadyInStore() > 0) {
                CliOutput.out("  cache→store: promoted "
                        + report.promoted()
                        + " Class-C blob(s) ("
                        + report.alreadyInStore()
                        + " already durable)");
            }
        } catch (Exception e) {
            // Advisory — release already staged dist files.
            CliOutput.out("  cache→store: skipped (" + e.getMessage() + ")");
        }
    }

    private static int runBuild(Path dir, boolean skipTests, String modulesSpec, Path cacheDir) {
        List<String> args = new ArrayList<>();
        args.add("build");
        args.add("-C");
        args.add(dir.toString());
        if (skipTests) args.add("--skip-tests");
        if (modulesSpec != null && !modulesSpec.isBlank()) {
            args.add("--modules");
            args.add(modulesSpec);
        }
        if (cacheDir != null) {
            args.add("--cache-dir");
            args.add(cacheDir.toString());
        }
        return Jk.execute(args.toArray(String[]::new));
    }

    private static int runNative(Path dir, Path cacheDir) {
        List<String> args = new ArrayList<>();
        args.add("native");
        args.add("-C");
        args.add(dir.toString());
        args.add("--skip-tests");
        if (cacheDir != null) {
            args.add("--cache-dir");
            args.add(cacheDir.toString());
        }
        return Jk.execute(args.toArray(String[]::new));
    }

    private static int runInstallLocal(Path dir, Path cacheDir) {
        List<String> args = new ArrayList<>();
        args.add("plugin");
        args.add("install-local");
        args.add("-C");
        args.add(dir.toString());
        if (cacheDir != null) {
            args.add("--cache-dir");
            args.add(cacheDir.toString());
        }
        int code = Jk.execute(args.toArray(String[]::new));
        // No plugin workers in the workspace is OK for non-jk projects (exit CONFIG).
        if (code == Exit.CONFIG) {
            CliOutput.out("jk release: no PluginMain workers to install-local (skipped)");
            return 0;
        }
        return code;
    }

    private static Path findEngineModule(Path workspaceRoot, ProjectInfo root) {
        if (!root.workspaceRoot()) {
            if (isEngine(root)) return workspaceRoot;
            return null;
        }
        int n = Math.min(root.moduleDirs().size(), root.moduleNames().size());
        for (int i = 0; i < n; i++) {
            Path mod = resolveModuleDir(workspaceRoot, root.moduleDirs().get(i));
            var info = BuildCommand.projectInfoOrNull(mod);
            if (info != null && isEngine(info)) return mod;
        }
        for (String d : root.moduleDirs()) {
            Path mod = resolveModuleDir(workspaceRoot, d);
            var info = BuildCommand.projectInfoOrNull(mod);
            if (info != null && isEngine(info)) return mod;
        }
        Path conventional = workspaceRoot.resolve("server/engine");
        if (Files.isRegularFile(conventional.resolve("jk.toml"))) return conventional;
        return null;
    }

    private static Path findCliModule(Path workspaceRoot, ProjectInfo root) {
        if (!root.workspaceRoot()) {
            if (isCli(root)) return workspaceRoot;
            return null;
        }
        for (String d : root.moduleDirs()) {
            Path mod = resolveModuleDir(workspaceRoot, d);
            var info = BuildCommand.projectInfoOrNull(mod);
            if (info != null && isCli(info)) return mod;
        }
        Path conventional = workspaceRoot.resolve("clients/cli");
        if (Files.isRegularFile(conventional.resolve("jk.toml"))) return conventional;
        return null;
    }

    private static Path resolveModuleDir(Path workspaceRoot, String dir) {
        Path p = Path.of(dir);
        return p.isAbsolute()
                ? p.normalize()
                : workspaceRoot.resolve(dir).toAbsolutePath().normalize();
    }

    private static boolean isEngine(ProjectInfo b) {
        return "cc.jumpkick.engine.EngineMain".equals(b.mainClass()) || "jk-engine".equals(b.name());
    }

    private static boolean isCli(ProjectInfo b) {
        return "cc.jumpkick.cli.Jk".equals(b.mainClass()) || "jk-cli".equals(b.name());
    }

    private static boolean isNativeEligible(Path cliDir) {
        var info = BuildCommand.projectInfoOrNull(cliDir);
        return info != null && "ALWAYS".equals(info.nativeMode());
    }

    /**
     * Workspace module paths minus every {@code [native] enabled = "always"} module — not just the
     * discovered CLI module — so {@code --skip-native} never re-enters any of them.
     */
    static List<String> modulesWithoutNativeAlways(Path workspaceRoot, ProjectInfo root) {
        List<String> keep = new ArrayList<>();
        for (String m : root.moduleDirs()) {
            Path mod = resolveModuleDir(workspaceRoot, m);
            if (isNativeEligible(mod)) continue;
            keep.add(m);
        }
        return keep;
    }

    private static Path findEngineAssembly(Path engineDir) throws IOException {
        if (engineDir == null) return null;
        var info = BuildCommand.projectInfoOrNull(engineDir);
        if (info != null && !info.assemblyJarPath().isBlank()) {
            Path assembly = Path.of(info.assemblyJarPath());
            if (Files.isRegularFile(assembly)) return assembly;
        }
        // Sometimes version in toml differs; scan target/
        Path target = engineDir.resolve("target");
        if (!Files.isDirectory(target)) return null;
        try (var stream = Files.list(target)) {
            return stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith("-all.jar") && n.contains("engine");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Prefer a built native CLI under the cli module; otherwise the running {@code jk} (bootstrap
     * for dogfood when Graal is unavailable).
     */
    private static Path resolveClientBinary(Path cliDir) {
        if (cliDir != null) {
            Path nativeBin = findNativeClient(cliDir);
            if (nativeBin != null) return nativeBin;
        }
        return Path.of(resolveRunningJkExe());
    }

    private static boolean isNativeClientPath(Path cliDir, Path clientBin) {
        if (cliDir == null || clientBin == null) return false;
        Path nativeBin = findNativeClient(cliDir);
        if (nativeBin == null) return false;
        try {
            return Files.isSameFile(nativeBin, clientBin);
        } catch (IOException e) {
            return nativeBin
                    .toAbsolutePath()
                    .normalize()
                    .equals(clientBin.toAbsolutePath().normalize());
        }
    }

    private static Path findNativeClient(Path cliDir) {
        // pure-jk native-image uses project name (jk-cli); Gradle nativeCompile uses imageName "jk".
        // Only these ship-layout paths — do not walk target/ (nested CLI suites plant
        // target/test-jk-home/bin/jk, which is a bootstrap copy, not a native image).
        List<Path> candidates = List.of(
                cliDir.resolve("target/jk"),
                cliDir.resolve("target/jk-cli"),
                cliDir.resolve("target/native/nativeCompile/jk"),
                cliDir.resolve("build/native/nativeCompile/jk"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p;
        }
        // Workspace central out tree: <workspace>/target/clients/cli/jk
        try {
            var root = cc.jumpkick.config.WorkspaceLocator.findRoot(cliDir);
            if (root.isPresent()) {
                Path rel = root.get().relativize(cliDir.toAbsolutePath().normalize());
                for (String name : List.of("jk", "jk-cli")) {
                    Path p = root.get().resolve("target").resolve(rel).resolve(name);
                    if (Files.isRegularFile(p) && Files.isExecutable(p)) return p;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    private static String resolveRunningJkExe() {
        String env = System.getenv("JK_EXE");
        if (env != null && !env.isBlank() && Files.isRegularFile(Path.of(env))) return env;
        try {
            var cmd = ProcessHandle.current().info().command();
            if (cmd.isPresent()) {
                Path p = Path.of(cmd.get());
                if (Files.isRegularFile(p)) return p.toAbsolutePath().toString();
            }
        } catch (RuntimeException ignored) {
        }
        // PATH lookup
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path cand = Path.of(dir, "jk");
                if (Files.isRegularFile(cand)) return cand.toAbsolutePath().toString();
            }
        }
        return "jk";
    }
}
