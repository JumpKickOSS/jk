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
 * {@code jk assembly} — build the assembly (fat) jar. Same packaging graph as {@code jk build} with
 * {@code [application] assembly = true}; does not invent a second graph.
 *
 * <p>Alias: {@code jk assemble}. Errors with a one-line fix when {@code assembly} is not set.
 */
public final class AssemblyCommand implements CliCommand {

    private final BuildCommand build = new BuildCommand();

    @Override
    public String name() {
        return "assembly";
    }

    @Override
    public String description() {
        return "Build an assembly jar — requires [application] assembly = true";
    }

    @Override
    public List<String> aliases() {
        return List.of("assemble");
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
        if (!project.assembly()) {
            CliOutput.err(
                    """
                    jk assembly: assembly packaging is off — add to jk.toml:

                      [application]
                      main = "your.Main"   # optional but usual for a runnable jar
                      assembly = true

                    Then re-run `jk assembly` (or `jk build`). See docs/features/packaging.md.
                    """.stripIndent());
            return Exit.CONFIG;
        }
        return build.run(in);
    }
}
