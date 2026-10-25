// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.BuildOptions;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk snapshot} — {@code jk install} for the inner loop: the project (or every workspace
 * member) is installed at its declared version plus {@code -SNAPSHOT}, replacing the snapshot a
 * previous run left. {@code jk.toml} keeps the release version. Same phases, tests and install
 * target as {@code jk install}.
 */
public final class SnapshotCommand implements CliCommand {

    @Override
    public String name() {
        return "snapshot";
    }

    @Override
    public String description() {
        return "Install a -SNAPSHOT of this project (inner loop)";
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>(List.of(
                Opt.value(
                        "<m2|jk>",
                        "Where the install goes: m2 (the Maven local repository, default) or jk (jk's private repository)",
                        "--destination"),
                Opt.value("<dir>", "Override the tool state directory.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory.", "--bin-dir").hide(),
                Opt.value("<dir>", "Override the lib directory.", "--lib-dir").hide(),
                Opt.value("<dir>", "Override the local Maven repo root (~/.m2).", "--m2-dir")
                        .hide()));
        opts.addAll(BuildFlags.options(WorkspaceTarget.INSTALL.tests(), BuildFlags.Affected.MODULES));
        return opts;
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        GlobalOptions global = GlobalOptions.from(in);
        BuildFlags flags;
        try {
            flags = BuildFlags.from(in);
        } catch (IllegalArgumentException e) {
            CommandWedge.printFail("Snapshot", e.getMessage());
            return Exit.CONFIG;
        }
        flags.installSession();

        String destinationWord = in.value(InstallDestination.KEY).orElse(null);
        InstallDestination destination = destinationWord == null
                ? null
                : InstallDestination.parse(destinationWord).orElse(null);
        if (destinationWord != null && destination == null) {
            CommandWedge.printFail("Snapshot", "--destination must be m2 or jk, not `" + destinationWord + "`");
            return Exit.USAGE;
        }
        Path base = global.workingDir();
        if (!Files.isRegularFile(ManifestPaths.manifestIn(base))) {
            CommandWedge.printFail("Snapshot", "no jk.toml here — run `jk snapshot` inside a project");
            return Exit.USAGE;
        }

        BuildOptions buildOpts = new BuildOptions();
        buildOpts.skipTests = flags.skipTests();
        InstallCommand install = new InstallCommand(global, buildOpts);
        install.snapshot = true;
        install.destinationOverride = destination;
        install.cacheDirOverride = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        install.stateDirOverride = in.value("state-dir").map(CliPaths::abs).orElse(null);
        install.binDirOverride = in.value("bin-dir").map(CliPaths::abs).orElse(null);
        install.libDirOverride = in.value("lib-dir").map(CliPaths::abs).orElse(null);
        install.m2DirOverride = in.value("m2-dir").map(CliPaths::abs).orElse(null);
        install.jdksDir = CommonOpts.jdksDirValue(in);
        return install.runProjectInstallBuildPlan(base);
    }
}
