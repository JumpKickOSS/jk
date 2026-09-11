// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.ProjectContext;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.watch.AppWatchLoop;
import cc.jumpkick.cli.watch.SourceWatch;
import cc.jumpkick.command.VariantSelection;
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
 * Verb-only modes re-dispatch the normal commands. {@code run} uses {@link AppWatchLoop}.
 */
public final class WatchCommand implements CliCommand {

    private static final Set<String> VERBS = Set.of("compile", "test", "build", "run");

    @Override
    public String name() {
        return "watch";
    }

    @Override
    public String description() {
        return "Re-run a verb when sources change (jk dev = watch run)";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<>(List.of(
                CommonOpts.cacheDir(),
                CommonOpts.jdksDir(),
                Opt.value(
                        "<ms>",
                        "Debounce source changes (default " + SourceWatch.DEBOUNCE_MILLIS + " ms)",
                        "--debounce-ms"),
                Opt.flag("Run the app alone, no [dev.sidecars]", "--no-sidecars")));
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
        Path cacheOverride = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        Path jdksDir = CommonOpts.jdksDirValue(in);
        VariantSelection.install(in, global.workingDir());

        List<String> positionals = in.positionals();
        String verb;
        List<String> rest;
        if (positionals.isEmpty()) {
            // Bare `jk watch` needs a verb. (`jk dev` always injects `run` via DevCommand.)
            CommandWedge.printFail(
                    "Watch", "expected a verb — compile, test, build, or run (tip: `jk dev` = watch run)");
            return Exit.USAGE;
        }
        String first = positionals.getFirst().trim().toLowerCase(Locale.ROOT);
        if (VERBS.contains(first)) {
            verb = first;
            rest = positionals.size() > 1 ? positionals.subList(1, positionals.size()) : List.of();
        } else {
            CommandWedge.printFail("Watch", "unknown verb `" + first + "` (use compile, test, build, or run)");
            return Exit.USAGE;
        }

        Path projectDir = global.workingDir();
        var proj = ProjectContext.require(projectDir, "watch").orElse(null);
        if (proj == null) return Exit.CONFIG;

        long debounceMs;
        try {
            debounceMs = in.value("debounce-ms")
                    .map(s -> {
                        long v = Long.parseLong(s.trim());
                        if (v < 0 || v > 60_000) {
                            throw new NumberFormatException("out of range");
                        }
                        return v;
                    })
                    .orElse(SourceWatch.DEBOUNCE_MILLIS);
        } catch (NumberFormatException e) {
            CommandWedge.printFail("Watch", "invalid --debounce-ms (use an integer 0..60000)");
            return Exit.USAGE;
        }

        return switch (verb) {
            case "run" ->
                new AppWatchLoop(
                                global,
                                jdksDir,
                                "jk watch run",
                                in.flag("no-sidecars").orElse(false),
                                recompiler(global))
                        .run(projectDir, AppWatchLoop.cache(cacheOverride), rest);
            case "compile", "test", "build" -> verbLoop(verb, projectDir, global, debounceMs);
            default -> {
                CommandWedge.printFail("Watch", "unknown verb `" + verb + "` (use compile, test, build, or run)");
                yield Exit.USAGE;
            }
        };
    }

    /** The dev loop's recompile: the same single-plan-or-workspace choice as {@code jk compile}. */
    private static AppWatchLoop.Compiler recompiler(GlobalOptions global) {
        var labels = new CompileRun.Labels("Watch", "Recompiled", "Compile failed");
        return (projectDir, cache) ->
                CompileRun.resolve(projectDir, null, null, false, null).run(labels, global, cache) == 0;
    }

    private static int verbLoop(String verb, Path projectDir, GlobalOptions global, long debounceMs) throws Exception {
        int code = runVerb(verb);
        if (code != 0) {
            CommandWedge.printFail("Watch", "initial " + verb + " failed (exit " + code + "); watching anyway");
        }

        List<Path> roots = SourceWatch.defaultRoots(projectDir);
        if (roots.isEmpty()) roots = List.of(projectDir);

        CommandWedge.printWorking(
                "Watch", verb + " on change (src/, test/src/, jk.toml; debounce " + debounceMs + "ms). Ctrl-C stops.");

        try (SourceWatch watch = SourceWatch.open(projectDir, roots, debounceMs)) {
            while (true) {
                watch.awaitChange();
                CommandWedge.printWorking("Watch", "change detected — " + verb);
                code = runVerb(verb);
                if (code != 0) {
                    CommandWedge.printFail("Watch", verb + " failed (exit " + code + ")");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CommandWedge.printFail("Watch", "interrupted");
            return Exit.INTERRUPTED;
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
