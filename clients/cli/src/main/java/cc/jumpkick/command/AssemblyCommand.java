// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk assembly} — Mill-style name for the fat/shadow jar path. Same packaging graph as
 * {@code jk build} with {@code [application] shadow-jar = true}; does not invent a second graph.
 *
 * <p>Errors with a one-line fix when {@code shadow-jar} is not set.
 */
public final class AssemblyCommand implements CliCommand {

    private final BuildCommand build = new BuildCommand();

    @Override
    public String name() {
        return "assembly";
    }

    @Override
    public String description() {
        return "Build a fat (shadow) jar — requires [application] shadow-jar = true";
    }

    @Override
    public List<String> aliases() {
        return List.of("fat-jar", "shadow");
    }

    @Override
    public List<Opt> options() {
        return build.options();
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path toml = dir.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) {
            CliOutput.err("jk assembly: no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        JkBuild project = JkBuildParser.parse(toml);
        if (!project.shadowJar()) {
            CliOutput.err(
                    "jk assembly: fat jar packaging is off — add to jk.toml:\n"
                            + "\n"
                            + "  [application]\n"
                            + "  main = \"your.Main\"   # optional but usual for a runnable jar\n"
                            + "  shadow-jar = true\n"
                            + "\n"
                            + "Then re-run `jk assembly` (or `jk build`). See docs/features/packaging.md.");
            return Exit.CONFIG;
        }
        return build.run(in);
    }
}
