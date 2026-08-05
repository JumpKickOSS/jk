// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.DenyReport;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.util.JkDirs;
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

    private static final BuildPlanKey<DenyReport> REPORT = BuildPlanKey.of("deny-report", DenyReport.class);

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path projectDir = global.workingDir();
        Path jkBuild = projectDir.resolve("jk.toml");
        if (!Files.exists(jkBuild)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Deny", jkBuild + " not found."));
            return Exit.NO_INPUT;
        }
        Path cache = JkDirs.cache();
        int lockCode = cc.jumpkick.cli.EnsureFreshLock.ensure(projectDir, cache, global, "Deny");
        if (lockCode != 0) return lockCode;
        Path lockPath = cc.jumpkick.lock.LockPaths.lockFile(projectDir);
        if (!Files.exists(lockPath)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Deny",
                    "no jk-lock.toml in " + cc.jumpkick.cli.PathDisplay.styledRaw(projectDir)
                            + " (lock refresh did not produce one)."));
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

        BuildPlan pipeline = BuildPlan.builder("deny").addTask(check).build();
        BuildPlanResult result = BuildPlanConsole.run(pipeline, BuildPlanConsole.modeFor(global), cache);
        if (!result.success()) return 1;

        DenyReport report = pipeline.get(REPORT).orElseThrow();
        if (report.violationCount() == 0) {
            if (!global.outputIsJson())
                cc.jumpkick.cli.tui.CommandWedge.printOk(
                        "Deny", report.checked() + " package(s) checked — no violations.");
            return 0;
        }
        CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Deny", report.violationCount() + " violation(s):"));
        for (int i = 0; i < report.violationCount(); i++) {
            CliOutput.err("  "
                    + Coords.module(report.modules().get(i), report.versions().get(i)) + " — "
                    + report.reasons().get(i));
        }
        return 1;
    }
}
