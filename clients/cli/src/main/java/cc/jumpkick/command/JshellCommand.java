// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CliPaths;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.EnsureFreshLock;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.ExecPlan;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code jk jshell} (alias {@code jk repl}) — open JDK jshell on the module's compile classpath
 * . Ensures a build unless {@code --no-build}.
 */
public final class JshellCommand implements CliCommand {

    @Override
    public String name() {
        return "jshell";
    }

    @Override
    public List<String> aliases() {
        return List.of("repl");
    }

    @Override
    public String description() {
        return "jshell on this module's compile classpath";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Skip build; use existing classes + lock classpath only.", "--no-build"),
                Opt.value(
                                "<dir>",
                                "Override cache-tier directory (action outputs; not the artifact store). Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide(),
                CommonOpts.jdksDir(),
                Opt.flag("Skip tests during the preparatory build.", "--skip-tests"));
    }

    @Override
    public List<Param> parameters() {
        // Extra args forwarded to jshell (e.g. --startup, scripts).
        return List.of(Param.of("jshell-args", Arity.ZERO_OR_MORE, "Extra arguments forwarded to jshell"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        var proj = ProjectContext.require(dir, "jshell").orElse(null);
        if (proj == null) return Exit.CONFIG;

        Path jshellBin = findJshell();
        if (jshellBin == null) {
            CommandWedge.printFail(
                    "JShell",
                    "jshell not found under java.home="
                            + System.getProperty("java.home")
                            + " (need a full JDK, not a JRE)");
            return Exit.CONFIG;
        }

        boolean noBuild = in.isSet("no-build");
        Path cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(JkDirs.cache());

        if (!noBuild) {
            // Quiet preparatory build so classes exist.
            Invocation.Builder bb = Invocation.builder();
            bb.flag("skip-tests", true);
            bb.flag("no-timeline", true);
            // Preserve -C / working dir for the nested build.
            bb.putValue("directory", dir.toString());
            if (in.value("cache-dir").isPresent()) {
                bb.putValue("cache-dir", cacheDir.toString());
            }
            in.value(CommonOpts.JDKS_DIR).ifPresent(v -> bb.putValue(CommonOpts.JDKS_DIR, v));
            int code = new BuildCommand().run(bb.build());
            if (code != 0) {
                CommandWedge.printFail(
                        "JShell", "preparatory build failed (exit " + code + "); try --no-build after a green build");
                return code;
            }
        }

        int lockCode = EnsureFreshLock.ensure(dir, cacheDir, global, "JShell");
        if (lockCode != 0) return lockCode;

        ExecPlan plan;
        try {
            plan = EngineClient.execPlan(EnginePaths.current(), dir, cacheDir, "jshell", null, null);
        } catch (IOException e) {
            CommandWedge.printFail("JShell", e.getMessage());
            return Exit.SOFTWARE;
        }
        if (plan.error() != null && !plan.error().isBlank()) {
            CommandWedge.printFail("JShell", plan.error());
            return Exit.CONFIG;
        }
        if (plan.display() != null && !plan.display().isBlank()) {
            CommandWedge.printChipErr(Glyphs.BANG, "JShell", plan.display());
        }
        List<String> cp =
                plan.libPaths() == null || plan.libPaths().isEmpty() ? List.of(plan.mainJar()) : plan.libPaths();
        String classpath = Classpaths.join(cp.stream().map(Path::of).toList());

        List<String> cmd = new ArrayList<>();
        cmd.add(jshellBin.toString());
        cmd.add("--class-path");
        cmd.add(classpath);
        // Local execution: remote demux hits IOContext.charset() UOE on JDK 25 startup.
        List<String> extra = in.positionals();
        if (!hasExecutionSpec(extra)) {
            cmd.add("--execution");
            cmd.add("local");
        }
        // Forward extra positionals to jshell.
        cmd.addAll(extra);

        if (global.verbose) {
            CommandWedge.printWorking(
                    "JShell",
                    cmd.stream().map(s -> s.contains(" ") ? "\"" + s + "\"" : s).collect(Collectors.joining(" ")));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        Process p = CliOutput.handOffTerminal(pb);
        // Skip the gap only once the exec actually started — a failed start() still owns
        // the terminal, and its error wedge has earned the envelope's trailing blank.
        CliOutput.skipTrailingBlank();
        int exit = p.waitFor();
        return exit;
    }

    /** True when the user already chose an execution engine ({@code --execution …}). */
    static boolean hasExecutionSpec(List<String> args) {
        for (String a : args) {
            if (a == null) continue;
            if (a.equals("--execution") || a.startsWith("--execution=")) return true;
        }
        return false;
    }

    /** Prefer {@code $JAVA_HOME/bin/jshell}, then {@code java.home}/bin/jshell. */
    static Path findJshell() {
        List<Path> candidates = new ArrayList<>();
        String env = System.getenv("JAVA_HOME");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env, "bin", "jshell"));
            candidates.add(Path.of(env, "bin", "jshell.exe"));
        }
        String home = System.getProperty("java.home");
        if (home != null) {
            candidates.add(Path.of(home, "bin", "jshell"));
            candidates.add(Path.of(home, "bin", "jshell.exe"));
            // Sometimes java.home is jre/ under a JDK
            Path parent = Path.of(home).getParent();
            if (parent != null) {
                candidates.add(parent.resolve("bin/jshell"));
                candidates.add(parent.resolve("bin/jshell.exe"));
            }
        }
        for (Path c : candidates) {
            if (c != null && PathUtil.isRunnable(c)) return c;
            // On some systems isExecutable is false for scripts; isRegularFile is enough.
            if (c != null && Files.isRegularFile(c)) return c;
        }
        return null;
    }
}
