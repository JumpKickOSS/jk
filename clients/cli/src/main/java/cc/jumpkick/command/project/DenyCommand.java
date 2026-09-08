// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.EnsureFreshLock;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.DenyReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code jk deny} — apply the jk.toml {@code deny} policy against locked deps. Exits non-zero on
 * violation. Parse/check run engine-side so a misread client cannot silently pass.
 */
public final class DenyCommand implements CliCommand {

    @Override
    public String name() {
        return "deny";
    }

    @Override
    public String description() {
        return "Apply the project's license / source / yanked policy";
    }

    private static final BuildPlanKey<DenyReport> REPORT = BuildPlanKey.scalar("deny-report", DenyReport.class);

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path projectDir = global.workingDir();
        Path jkBuild = projectDir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(jkBuild)) {
            CommandWedge.printFail("Deny", jkBuild + " not found.");
            return Exit.NO_INPUT;
        }
        Path cache = JkDirs.cache();
        int lockCode = EnsureFreshLock.ensure(projectDir, cache, global, "Deny");
        if (lockCode != 0) return lockCode;
        Path lockPath = LockPaths.lockFile(projectDir);
        if (!Files.exists(lockPath)) {
            CommandWedge.printFail(
                    "Deny",
                    "no jk-lock.toml in " + PathDisplay.styledRaw(projectDir) + " (lock refresh did not produce one).");
            return Exit.CONFIG;
        }

        Task check = Task.builder("check")
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("check policy against lock");
                    DenyReport report = EngineClient.denyCheck(EnginePaths.current(), projectDir);
                    if (report.error() != null) throw new IOException(report.error());
                    ctx.put(REPORT, report);
                    ctx.progress(1);
                })
                .build();

        BuildPlan plan =
                BuildPlan.builder("deny").stateKeys(REPORT).addTask(check).build();
        BuildPlanResult result = BuildPlanConsole.run(plan, BuildPlanConsole.modeFor(global), cache);
        if (!result.success()) return 1;

        DenyReport report = plan.get(REPORT).orElseThrow();
        if (report.violationCount() == 0) {
            if (!global.outputIsJson())
                CommandWedge.printOk("Deny", report.checked() + " package(s) checked — no violations.");
            return 0;
        }
        CommandWedge.printFail("Deny", report.violationCount() + " violation(s):");
        for (int i = 0; i < report.violationCount(); i++) {
            CliOutput.err("  "
                    + Coords.module(report.modules().get(i), report.versions().get(i)) + " — "
                    + report.reasons().get(i));
        }
        return 1;
    }
}
