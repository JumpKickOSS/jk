// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code jk jshell} (alias {@code jk repl}) — open JDK jshell on the module's compile classpath
 * (JK-1036). Ensures a build unless {@code --no-build}.
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
        return "Open jshell with this module's compile classpath (after build unless --no-build)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Skip build; use existing classes + lock classpath only.", "--no-build"),
                Opt.value(
                                "<dir>",
                                "Override the download/action cache (CAS). Default: $JK_CACHE_DIR or $JK_HOME/cache (~/.jk/cache).",
                                "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
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
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "JShell",
                    "jshell not found under java.home="
                            + System.getProperty("java.home")
                            + " (need a full JDK, not a JRE)"));
            return Exit.CONFIG;
        }

        boolean noBuild = in.isSet("no-build");
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(JkDirs.cache());

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
            if (in.value("jdks-dir").isPresent()) {
                bb.putValue("jdks-dir", in.value("jdks-dir").get());
            }
            int code = new BuildCommand().run(bb.build());
            if (code != 0) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                        "JShell", "preparatory build failed (exit " + code + "); try --no-build after a green build"));
                return code;
            }
        }

        if (!proj.isLocked()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("JShell", "no jk-lock.toml — run `jk lock` first"));
            return Exit.CONFIG;
        }

        JkBuild build = JkBuildParser.parse(proj.buildFile());
        if (build.isWorkspaceRoot()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "JShell", "run from a module directory (workspace roots have no single compile classpath)"));
            return Exit.CONFIG;
        }

        BuildLayout layout = BuildLayout.of(dir, build);
        Path classes = layout.classesDir();
        if (!Files.isDirectory(classes)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "JShell", "no classes at " + classes + " — run `jk build --skip-tests` or drop `--no-build`"));
            return Exit.CONFIG;
        }

        Lockfile lock = LockfileReader.read(proj.lockFile());
        Cas cas = JkStores.cas(cacheDir.resolve("cas"));
        List<Path> depCp = new ClasspathResolver(cas).classpathFor(lock, ClasspathResolver.COMPILE_MAIN);

        List<String> cp = new ArrayList<>();
        cp.add(classes.toString());
        int missing = 0;
        for (Path p : depCp) {
            if (p == null) continue;
            if (Files.exists(p)) {
                cp.add(p.toString());
            } else {
                missing++;
            }
        }
        if (missing > 0) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.chip(
                    cc.jumpkick.cli.tui.Glyphs.BANG,
                    "JShell",
                    missing + " lock classpath entry(ies) missing on disk — run `jk sync`"));
        }
        String classpath = String.join(java.io.File.pathSeparator, cp);

        List<String> cmd = new ArrayList<>();
        cmd.add(jshellBin.toString());
        cmd.add("--class-path");
        cmd.add(classpath);
        // Forward extra positionals to jshell.
        cmd.addAll(in.positionals());

        if (global.verbose) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "JShell",
                    cmd.stream().map(s -> s.contains(" ") ? "\"" + s + "\"" : s).collect(Collectors.joining(" "))));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.inheritIO();
        Process p = pb.start();
        int exit = p.waitFor();
        return exit;
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
            if (c != null && Files.isExecutable(c)) return c;
            // On some systems isExecutable is false for scripts; isRegularFile is enough.
            if (c != null && Files.isRegularFile(c)) return c;
        }
        return null;
    }
}
