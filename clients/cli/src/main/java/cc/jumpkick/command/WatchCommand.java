// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.watch.AppWatchLoop;
import cc.jumpkick.cli.watch.SourceWatch;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live loops over a verb when sources change:
 *
 * <pre>
 *   jk watch compile | test | build | run
 *   jk dev   → alias for {@code watch run} (app + hot-reload)
 * </pre>
 *
 * Verb-only modes re-dispatch the normal commands. {@code run} uses {@link AppWatchLoop} (former
 * {@code DevCommand} body).
 */
public final class WatchCommand implements CliCommand {

    private static final Set<String> VERBS = Set.of("compile", "test", "build", "run");

    @Override
    public String name() {
        return "watch";
    }

    @Override
    public String description() {
        return "Re-run a verb (or the app) when sources change (`jk dev` = watch run)";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<>(List.of(
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install directory.", "--jdks-dir")
                        .hide()));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Override
    public List<Param> parameters() {
        // verb optional when invoked as `jk dev` (defaults to run); app args after `run`.
        return List.of(
                Param.of("verb", Arity.ZERO_OR_ONE, "compile | test | build | run (default run when invoked as dev)"),
                Param.of("args", Arity.ZERO_OR_MORE, "App args for watch run / dev."));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path cacheOverride = in.value("cache-dir").map(Path::of).orElse(null);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        VariantSelection.install(in, global.workingDir());

        List<String> positionals = in.positionals();
        String verb;
        List<String> rest;
        if (positionals.isEmpty()) {
            // Bare `jk watch` needs a verb. (`jk dev` always injects `run` via DevCommand.)
            CliOutput.err("jk watch: expected a verb — compile, test, build, or run");
            CliOutput.err("  tip: `jk dev` is short for `jk watch run`");
            return Exit.USAGE;
        }
        String first = positionals.getFirst().trim().toLowerCase(Locale.ROOT);
        if (VERBS.contains(first)) {
            verb = first;
            rest = positionals.size() > 1 ? positionals.subList(1, positionals.size()) : List.of();
        } else {
            CliOutput.err("jk watch: unknown verb `" + first + "` (use compile, test, build, or run)");
            return Exit.USAGE;
        }

        Path projectDir = global.workingDir();
        var proj = ProjectContext.require(projectDir, "watch").orElse(null);
        if (proj == null) return Exit.CONFIG;

        return switch (verb) {
            case "run" -> new AppWatchLoop(global, jdksDir, "jk watch run")
                    .run(projectDir, AppWatchLoop.cache(cacheOverride), rest);
            case "compile", "test", "build" -> verbLoop(verb, projectDir, global);
            default -> {
                CliOutput.err("jk watch: unknown verb `" + verb + "` (use compile, test, build, or run)");
                yield Exit.USAGE;
            }
        };
    }

    private static int verbLoop(String verb, Path projectDir, GlobalOptions global) throws Exception {
        int code = runVerb(verb);
        if (code != 0) {
            CliOutput.err("jk watch: initial " + verb + " failed (exit " + code + "); watching anyway");
        }

        List<Path> roots = SourceWatch.defaultRoots(projectDir);
        if (roots.isEmpty()) roots = List.of(projectDir);

        CliOutput.err("jk watch: " + verb + " on change. Ctrl-C stops.");

        try (SourceWatch watch = SourceWatch.open(projectDir, roots)) {
            while (true) {
                watch.awaitChange();
                CliOutput.err("jk watch: change detected — " + verb);
                code = runVerb(verb);
                if (code != 0) {
                    CliOutput.err("jk watch: " + verb + " failed (exit " + code + ")");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CliOutput.err("jk watch: interrupted");
            return 130;
        }
    }

    private static int runVerb(String verb) throws Exception {
        return switch (verb) {
            case "compile" -> new CompileCommand().run(Invocation.builder().build());
            case "test" -> new TestCommand().run(Invocation.builder().build());
            case "build" -> {
                Invocation.Builder bb = Invocation.builder();
                bb.flag("skip-tests", true);
                yield new BuildCommand().run(bb.build());
            }
            default -> Exit.USAGE;
        };
    }
}
