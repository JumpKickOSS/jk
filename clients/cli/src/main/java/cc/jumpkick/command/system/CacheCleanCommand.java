// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk cache clean}: reclaim cache space to the configured budget, oldest first, through the
 * engine's maintenance pass; {@code --m2} cleans JumpKick's unused downloads from {@code ~/.m2}.
 */
public final class CacheCleanCommand implements CliCommand {
    @Override
    public String name() {
        return "clean";
    }

    @Override
    public List<String> aliases() {
        return List.of("prune");
    }

    @Override
    public String description() {
        return "Reclaim cache space to the configured budget (oldest first)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                CommonOpts.cacheDir(),
                Opt.flag("Print what would be removed; touch nothing.", "-U", "--dry-run"),
                Opt.flag("Clean JumpKick's unused downloads from ~/.m2 instead of the cache.", "-m", "--m2"),
                Opt.value("<DAYS>", "With --m2: days unused (default: [m2] clean-after-days)", "-t", "--older-than"));
    }

    @Override
    public MachineOutput machineOutput() {
        return MachineOutput.JSONL;
    }

    @Override
    public int run(Invocation in) throws IOException {
        Path cacheDir = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        boolean dryRun = in.isSet("dry-run");
        GlobalOptions global = GlobalOptions.from(in);

        Path root = CacheCommand.resolveCacheRoot(cacheDir);
        if (in.isSet("m2")) {
            int days = in.value("older-than").map(Integer::parseInt).orElse(0);
            return runHosted(root, false, dryRun, global, "m2", CliPaths.abs(""), days);
        }
        if (!Files.isDirectory(root)) {
            CliOutput.out("Nothing to clean: " + root + " does not exist");
            return 0;
        }

        return runHosted(root, cacheDir == null, dryRun, global, "prune", null, 0);
    }

    /** The engine-hosted foreground path: send the request, explain any wait, render the stream. */
    private static int runHosted(
            Path root,
            boolean defaultCacheDir,
            boolean dryRun,
            GlobalOptions global,
            String op,
            @Nullable Path projectRoot,
            int olderThanDays) {
        // Settled from the terminal plan-finish before the console listener renders the line.
        var summary = new EngineRequests.CacheMaintSummary[1];
        ConsoleSpec spec = cleanSpec(
                dryRun,
                () -> summary[0] != null ? summary[0].files() : 0L,
                () -> summary[0] != null ? summary[0].bytes() : 0L,
                "m2".equals(op) ? "the Maven local repository" : "cache");
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        BuildPlanResult result;
        try {
            result = EngineClient.runCacheMaintenance(
                    EnginePaths.current(),
                    new EngineRequests.CacheMaintRequest(op, root, dryRun, defaultCacheDir, projectRoot, olderThanDays),
                    steps -> BuildPlanConsole.chooseConsoleListener(steps, mode, spec, "Cache"),
                    CacheCommand::printWait,
                    summary);
        } catch (IOException e) {
            CommandWedge.printFail("Cache", e.getMessage());
            return Exit.SOFTWARE;
        }
        long files = summary[0] != null ? summary[0].files() : 0L;
        if (result.success() && !dryRun && files > 0) {
            boolean m2 = "m2".equals(op);
            ChangeJournal.note(
                    m2 ? "Maven local repository" : "cache tier",
                    Change.CLEAN,
                    "cleaned " + (m2 ? "JumpKick's unused downloads from ~/.m2" : "the cache tier") + ": "
                            + CacheCommand.fmtCount(files) + " files, " + CacheCommand.fmtBytes(summary[0].bytes())
                            + " freed",
                    null);
        }
        return result.success() ? 0 : 1;
    }

    /** The Cache chip spec, naming what is being cleaned; counts are read lazily, at result-line render time. */
    static ConsoleSpec cleanSpec(boolean dryRun, LongSupplier files, LongSupplier bytes, String what) {
        return new ConsoleSpec(
                "Cache",
                r -> {
                    long f = Math.max(0, files.getAsLong());
                    long b = Math.max(0, bytes.getAsLong());
                    if (dryRun) {
                        if (f == 0) return "Dry run: nothing to clean up.";
                        return "Dry run: would remove "
                                + f + " " + (f == 1 ? "file" : "files")
                                + ", " + CacheCommand.fmtBytes(b) + " reclaimable.";
                    }
                    if (f == 0) return "Finished cleaning " + what + ". Nothing to clean up.";
                    return "Finished cleaning " + what + ". "
                            + f + " " + (f == 1 ? "file" : "files")
                            + " removed, " + CacheCommand.fmtBytes(b) + " reclaimed.";
                },
                r -> "Failed to clean " + what + ".",
                true);
    }
}
