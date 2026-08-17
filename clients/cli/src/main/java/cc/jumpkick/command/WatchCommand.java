// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

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
        return "Re-run a verb when sources change (jk dev = watch run)";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<>(List.of(
                cc.jumpkick.cli.CommonOpts.cacheDir(),
                Opt.value("<dir>", "Override the JDK install directory.", "--jdks-dir")
                        .hide(),
                Opt.value(
                        "<ms>",
                        "Debounce source changes (default " + SourceWatch.DEBOUNCE_MILLIS + " ms)",
                        "--debounce-ms")));
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
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Watch", "expected a verb — compile, test, build, or run (tip: `jk dev` = watch run)");
            return Exit.USAGE;
        }
        String first = positionals.getFirst().trim().toLowerCase(Locale.ROOT);
        if (VERBS.contains(first)) {
            verb = first;
            rest = positionals.size() > 1 ? positionals.subList(1, positionals.size()) : List.of();
        } else {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Watch", "unknown verb `" + first + "` (use compile, test, build, or run)");
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
            cc.jumpkick.cli.tui.CommandWedge.printFail("Watch", "invalid --debounce-ms (use an integer 0..60000)");
            return Exit.USAGE;
        }

        return switch (verb) {
            case "run" ->
                new AppWatchLoop(global, jdksDir, "jk watch run")
                        .run(projectDir, AppWatchLoop.cache(cacheOverride), rest);
            case "compile", "test", "build" -> verbLoop(verb, projectDir, global, debounceMs);
            default -> {
                cc.jumpkick.cli.tui.CommandWedge.printFail(
                        "Watch", "unknown verb `" + verb + "` (use compile, test, build, or run)");
                yield Exit.USAGE;
            }
        };
    }

    private static int verbLoop(String verb, Path projectDir, GlobalOptions global, long debounceMs) throws Exception {
        int code = runVerb(verb);
        if (code != 0) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Watch", "initial " + verb + " failed (exit " + code + "); watching anyway");
        }

        List<Path> roots = SourceWatch.defaultRoots(projectDir);
        if (roots.isEmpty()) roots = List.of(projectDir);

        cc.jumpkick.cli.tui.CommandWedge.printWorking(
                "Watch", verb + " on change (src/, test/src/, jk.toml; debounce " + debounceMs + "ms). Ctrl-C stops.");

        try (SourceWatch watch = SourceWatch.open(projectDir, roots, debounceMs)) {
            while (true) {
                watch.awaitChange();
                cc.jumpkick.cli.tui.CommandWedge.printWorking("Watch", "change detected — " + verb);
                code = runVerb(verb);
                if (code != 0) {
                    cc.jumpkick.cli.tui.CommandWedge.printFail("Watch", verb + " failed (exit " + code + ")");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cc.jumpkick.cli.tui.CommandWedge.printFail("Watch", "interrupted");
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
