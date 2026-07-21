// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk assembly} — build a bundled app jar. Same packaging graph as {@code jk build} when
 * {@code [application] assembly = true} (fat) or {@code assembly = "shrink"} (R8); does not invent a
 * second graph.
 *
 * <p>CLI one-offs: {@code --fat} / {@code --shrink} override {@code jk.toml} for this invocation only
 * (packaging mode is part of the action cache key). {@code --write-config} surgically sets {@code
 * assembly} in {@code jk.toml}. Alias: {@code jk assemble}.
 */
public final class AssemblyCommand implements CliCommand {

    private final BuildCommand build = new BuildCommand();

    @Override
    public String name() {
        return "assembly";
    }

    @Override
    public String description() {
        return "Build an assembly/shrink jar (jk.toml assembly, or --fat / --shrink for one-off)";
    }

    @Override
    public List<String> aliases() {
        return List.of("assemble");
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>(build.options());
        opts.add(Opt.flag("One-off fat assembly jar (overrides jk.toml for this run only).", "--fat"));
        opts.add(Opt.flag("One-off R8 shrunk jar (overrides jk.toml for this run only).", "--shrink"));
        opts.add(Opt.flag(
                "Surgically set [application].assembly in jk.toml to match --fat or --shrink.",
                "--write-config"));
        return opts;
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path toml = dir.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Assembly", "no jk.toml in " + PathDisplay.styledRaw(dir)));
            return Exit.CONFIG;
        }

        boolean fat = in.isSet("fat");
        boolean shrink = in.isSet("shrink");
        boolean writeConfig = in.isSet("write-config");
        if (fat && shrink) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Assembly", "choose one of --fat or --shrink (not both)"));
            return Exit.USAGE;
        }
        if (writeConfig && !fat && !shrink) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Assembly", "--write-config requires --fat or --shrink"));
            return Exit.USAGE;
        }

        JkBuild.AssemblyMode overrideMode = fat
                ? JkBuild.AssemblyMode.FAT
                : shrink ? JkBuild.AssemblyMode.SHRINK : null;

        if (writeConfig && overrideMode != null) {
            String original = Files.readString(toml);
            String edited = JkBuildEditor.setAssemblyMode(original, overrideMode);
            if (!edited.equals(original)) {
                Files.writeString(toml, edited);
                CliOutput.err("jk assembly: wrote [application] assembly = "
                        + (overrideMode == JkBuild.AssemblyMode.SHRINK ? "\"shrink\"" : "true")
                        + " to " + PathDisplay.styledRaw(toml));
            } else {
                CliOutput.err("jk assembly: jk.toml already has assembly = "
                        + (overrideMode == JkBuild.AssemblyMode.SHRINK ? "\"shrink\"" : "true"));
            }
        }

        if (overrideMode != null) {
            // CLI wins over jk.toml for this process/session (also rides the engine wire).
            SessionContext.install(SessionContext.current()
                    .withAssemblyOverride(overrideMode == JkBuild.AssemblyMode.SHRINK ? "shrink" : "fat"));
            if (!writeConfig) {
                String modeLabel = overrideMode == JkBuild.AssemblyMode.SHRINK ? "shrink (R8)" : "fat";
                CliOutput.err(
                        """
                        jk assembly: one-off packaging override — %s for this run only
                          (not written to jk.toml; action cache keys include the packaging mode)
                          make it permanent: jk assembly --%s --write-config
                        """
                                .formatted(
                                        modeLabel,
                                        overrideMode == JkBuild.AssemblyMode.SHRINK ? "shrink" : "fat")
                                .stripIndent()
                                .trim());
            }
        } else {
            JkBuild project = JkBuildParser.parse(toml);
            if (!project.assemblyMode().isBundled()) {
                CliOutput.err(
                        """
                        jk assembly: assembly packaging is off — pick one:

                          One-off (this run only):
                            jk assembly --fat
                            jk assembly --shrink

                          Persist in jk.toml:
                            jk assembly --fat --write-config
                            jk assembly --shrink --write-config

                          Or edit manually:
                            [application]
                            main = "your.Main"   # optional but usual for a runnable jar
                            assembly = true        # fat jar (all deps)
                            # assembly = "shrink"  # R8 small fat jar

                        Then re-run `jk assembly` (or `jk build`). See docs/features/packaging.md.
                        """.stripIndent());
                return Exit.CONFIG;
            }
        }
        return build.run(in);
    }
}
