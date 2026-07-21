// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
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
 * <p>Layout (JumpKick / multi-module with engine assembly):
 *
 * <pre>
 *   &lt;out&gt;/
 *     jk                         # native client binary, or the currently running jk (--jvm)
 *     lib/
 *       jk-engine-&lt;ver&gt;.jar      # engine assembly jar (version must match client)
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
        return "Assemble a local distribution (build + workers + ship layout)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Output directory. Default: target/dist (workspace or module).", "--out"),
                Opt.flag("Skip tests during the build step.", "--skip-tests"),
                Opt.flag("Prefer a native client binary under clients/cli (or module) target/.", "--native"),
                Opt.flag("Stage the currently running jk binary as the client (default if no --native).", "--jvm"),
                Opt.flag("Print the plan; build nothing and write nothing.", "--dry-run"),
                Opt.value(
                        "<sel>",
                        "Build only selected modules (passed to jk build). Default: whole workspace.",
                        "--modules"),
                cc.jumpkick.cli.CommonOpts.cacheDir());
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path rootToml = dir.resolve("jk.toml");
        if (!Files.isRegularFile(rootToml)) {
            CliOutput.err(
                    cc.jumpkick.cli.tui.CommandWedge.fail("Release", "no jk.toml in " + PathDisplay.styledRaw(dir)));
            return Exit.CONFIG;
        }

        boolean dryRun = in.isSet("dry-run");
        boolean skipTests = in.isSet("skip-tests");
        boolean preferNative = in.isSet("native");
        boolean preferJvm = in.isSet("jvm") || !preferNative;
        Path out = in.value("out").map(Path::of).orElse(dir.resolve("target").resolve("dist"));
        if (!out.isAbsolute()) out = dir.resolve(out).normalize();
        String modulesSpec = in.value("modules").orElse(null);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);

        JkBuild root = JkBuildParser.parse(rootToml);
        Path engineDir = findEngineModule(dir, root);
        Path cliDir = findCliModule(dir, root);

        if (dryRun) {
            CliOutput.out("jk release plan:");
            CliOutput.out("  working dir: " + dir);
            CliOutput.out("  out:         " + out);
            CliOutput.out("  skip-tests:  " + skipTests);
            CliOutput.out("  client:      " + (preferNative ? "native (if present) else running jk" : "running jk"));
            CliOutput.out("  engine:      " + (engineDir != null ? engineDir : "(none found)"));
            CliOutput.out("  cli:         " + (cliDir != null ? cliDir : "(none found)"));
            CliOutput.out("  then:        jk plugin install-local");
            return 0;
        }

        // 1) Build
        int code = runBuild(dir, skipTests, modulesSpec, cacheDir);
        if (code != 0) return code;

        // 2) Side-load PluginMain workers
        code = runInstallLocal(dir, cacheDir);
        if (code != 0) return code;

        // 3) Assemble ship layout
        Files.createDirectories(out.resolve("lib"));
        String version = JkVersion.VERSION;

        Path engineJar = findEngineAssembly(dir, root, engineDir);
        if (engineJar == null) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Release",
                    "no engine assembly jar found — need server/engine with assembly = true and a successful build"));
            return Exit.FAILURE;
        }
        Path stagedEngine = out.resolve("lib").resolve("jk-engine-" + version + ".jar");
        Files.copy(engineJar, stagedEngine, StandardCopyOption.REPLACE_EXISTING);
        CliOutput.out(cc.jumpkick.cli.tui.CommandWedge.ok(
                "Release", "engine → " + PathDisplay.styledRaw(stagedEngine)));

        Path clientBin = resolveClientBinary(preferNative, preferJvm, cliDir);
        if (clientBin == null || !Files.isRegularFile(clientBin)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Release",
                    "no client binary — pass --native after `jk native -C clients/cli`, or ensure `jk` is on PATH"));
            return Exit.FAILURE;
        }
        Path stagedClient = out.resolve("jk");
        Files.copy(clientBin, stagedClient, StandardCopyOption.REPLACE_EXISTING);
        try {
            stagedClient.toFile().setExecutable(true);
        } catch (Exception ignored) {
            // Windows / non-POSIX: install.sh / materialize may still work
        }
        CliOutput.out(cc.jumpkick.cli.tui.CommandWedge.ok(
                "Release", "client → " + PathDisplay.styledRaw(stagedClient) + " (from " + clientBin + ")"));

        CliOutput.out("");
        CliOutput.out(cc.jumpkick.cli.tui.CommandWedge.ok("Release", "distribution ready at " + PathDisplay.styledRaw(out)));
        CliOutput.out("  next: ./install.sh " + out.resolve("jk"));
        CliOutput.out("    or: jk self materialize " + out.resolve("jk") + " " + stagedEngine);
        return 0;
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

    private static Path findEngineModule(Path workspaceRoot, JkBuild root) throws IOException {
        if (!root.isWorkspaceRoot()) {
            if (isEngine(root)) return workspaceRoot;
            return null;
        }
        for (var e : WorkspaceLoader.loadModules(workspaceRoot, root).entrySet()) {
            if (isEngine(e.getValue())) return e.getKey();
        }
        // Convention fallback
        Path conventional = workspaceRoot.resolve("server/engine");
        if (Files.isRegularFile(conventional.resolve("jk.toml"))) return conventional;
        return null;
    }

    private static Path findCliModule(Path workspaceRoot, JkBuild root) throws IOException {
        if (!root.isWorkspaceRoot()) {
            if (isCli(root)) return workspaceRoot;
            return null;
        }
        for (var e : WorkspaceLoader.loadModules(workspaceRoot, root).entrySet()) {
            if (isCli(e.getValue())) return e.getKey();
        }
        Path conventional = workspaceRoot.resolve("clients/cli");
        if (Files.isRegularFile(conventional.resolve("jk.toml"))) return conventional;
        return null;
    }

    private static boolean isEngine(JkBuild b) {
        return "cc.jumpkick.engine.EngineMain".equals(b.mainClass())
                || "jk-engine".equals(b.project().name());
    }

    private static boolean isCli(JkBuild b) {
        return "cc.jumpkick.cli.Jk".equals(b.mainClass()) || "jk-cli".equals(b.project().name());
    }

    private static Path findEngineAssembly(Path workspaceRoot, JkBuild root, Path engineDir) throws IOException {
        if (engineDir == null) return null;
        JkBuild engine = JkBuildParser.parse(engineDir.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(engineDir, engine);
        Path assembly = layout.assemblyJar();
        if (Files.isRegularFile(assembly)) return assembly;
        // Sometimes version in toml differs; scan target/
        Path target = engineDir.resolve("target");
        if (!Files.isDirectory(target)) return null;
        try (var stream = Files.list(target)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith("-assembly.jar") && n.contains("engine");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Prefer native binary under the cli module when {@code --native}; otherwise the running jk
     * executable (self-host: bootstrap client + freshly built engine).
     */
    private static Path resolveClientBinary(boolean preferNative, boolean preferJvm, Path cliDir) {
        if (preferNative && cliDir != null) {
            Path nativeBin = findNativeClient(cliDir);
            if (nativeBin != null) return nativeBin;
            CliOutput.err("jk release: --native requested but no native binary under " + cliDir
                    + "/target — falling back to running jk");
        }
        return Path.of(resolveRunningJkExe());
    }

    private static Path findNativeClient(Path cliDir) {
        // jk native layout variants
        List<Path> candidates = List.of(
                cliDir.resolve("target/jk"),
                cliDir.resolve("target/native/nativeCompile/jk"),
                cliDir.resolve("build/native/nativeCompile/jk"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        Path target = cliDir.resolve("target");
        if (Files.isDirectory(target)) {
            try (var stream = Files.walk(target, 3)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals("jk"))
                        .findFirst()
                        .orElse(null);
            } catch (IOException e) {
                return null;
            }
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
