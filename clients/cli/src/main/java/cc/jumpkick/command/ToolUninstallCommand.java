// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.tool.LauncherName;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** {@code jk tool uninstall <name>} — remove an installed CLI tool. */
public final class ToolUninstallCommand implements CliCommand {

    @Override
    public String name() {
        return "uninstall";
    }

    @Override
    public String description() {
        return "Remove an installed CLI tool";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tool state directory. Default: $JK_STATE_DIR.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory. Default: ~/.jk/bin.", "--bin-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "name",
                Arity.ONE,
                "Launcher name (matches `jk tool list` first column), or a\n" + "build tool as <tool>:<version> ("
                        + BuildTool.slugs() + ")."));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String name = in.positionals().get(0);
        Integer buildTool = uninstallBuildTool(name);
        if (buildTool != null) return buildTool;
        var invalidName = LauncherName.validationError(name);
        if (invalidName.isPresent()) {
            CommandWedge.printFail("Uninstall", invalidName.get());
            return Exit.USAGE;
        }
        Path stateDir = in.value("state-dir").map(Path::of).orElse(null);
        Path binDirOverride = in.value("bin-dir").map(Path::of).orElse(null);
        Path state = stateDir != null ? stateDir : JkDirs.state();
        Path bin = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envDir = LauncherName.resolveChild(state.resolve("tools").resolve("envs"), name);
        Path launcher = LauncherName.resolveChild(bin, name);
        Path winLauncher = LauncherName.resolveChild(bin, name + ".cmd");

        boolean envExists = Files.isDirectory(envDir);
        // A leaf name in jk's private bin directory is owned regardless of launcher contents.
        List<Path> ours = Stream.of(launcher, winLauncher).filter(Files::exists).toList();

        if (!envExists && ours.isEmpty()) {
            CliOutput.out(name + " is not installed.");
            return 0;
        }

        if (envExists) {
            PathUtil.deleteRecursively(envDir); // under the state dir: jk's own, by containment
        }
        for (Path p : ours) {
            Files.deleteIfExists(p);
        }
        CommandWedge.printOk("Uninstall", "Removed " + name);
        return 0;
    }

    /**
     * Remove a provisioned build-tool distribution when {@code target} names one as
     * {@code <tool>:<version>}. Returns {@code null} when it does not, so the caller falls through
     * to the launcher-env shape.
     *
     * <p>A bare {@code kotlin} is deliberately not accepted: there may be several versions
     * installed, and guessing which one to delete is not a choice this command should make.
     */
    private Integer uninstallBuildTool(String target) throws IOException {
        int colon = target.indexOf(':');
        if (colon < 0) return null;
        Optional<BuildTool> tool = BuildTool.bySlug(target.substring(0, colon));
        if (tool.isEmpty()) return null;
        String version = target.substring(colon + 1);
        if (version.isBlank()) {
            CommandWedge.printFail("Uninstall", "no version after ':' in '" + target + "'");
            return Exit.USAGE;
        }
        ToolRegistry registry = new ToolRegistry(JkDirs.tools());
        Optional<InstalledTool> installed = registry.find(tool.get(), version);
        if (installed.isEmpty()) {
            CliOutput.out(target + " is not installed.");
            return 0;
        }
        PathUtil.deleteRecursively(installed.get().home());
        CommandWedge.printOk("Uninstall", "Removed " + target);
        return 0;
    }
}
