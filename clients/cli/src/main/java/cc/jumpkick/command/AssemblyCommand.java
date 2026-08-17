// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk assemble} — build a bundled app jar. Same packaging graph as {@code jk build} when
 * {@code [application] assembly = true} (fat) or {@code minified = true} (R8); does not invent a
 * second graph.
 *
 * <p>CLI one-offs: {@code --fat} / {@code --minified} override {@code jk.toml} for this invocation only
 * (packaging mode is part of the action cache key). {@code --write-config} surgically sets {@code
 * assembly} in {@code jk.toml}. Hidden alias: {@code jk assembly}.
 */
public final class AssemblyCommand implements CliCommand {

    private final BuildCommand build = new BuildCommand();

    @Override
    public String name() {
        return "assemble";
    }

    @Override
    public String description() {
        return "Build a fat or minified jar (see --fat / --minified)";
    }

    @Override
    public List<String> aliases() {
        return List.of("assembly");
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>(build.options());
        opts.add(Opt.flag("One-off fat jar (this run only)", "--fat"));
        opts.add(Opt.flag("One-off minified jar (this run only)", "--minified"));
        opts.add(Opt.flag("Write assembly mode into jk.toml", "--write-config"));
        return opts;
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        Path toml = dir.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Assemble", "no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }

        boolean fat = in.isSet("fat");
        boolean minified = in.isSet("minified");
        boolean writeConfig = in.isSet("write-config");
        if (fat && minified) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Assemble", "choose one of --fat or --minified (not both)");
            return Exit.USAGE;
        }
        if (writeConfig && !fat && !minified) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Assemble", "--write-config requires --fat or --minified");
            return Exit.USAGE;
        }

        boolean oneOff = fat || minified;
        String overrideLabel = minified ? "minified" : "fat";

        if (writeConfig && oneOff) {
            try {
                boolean changed = EngineEdits.apply(
                        toml, "set-artifacts", List.of(String.valueOf(true), String.valueOf(minified)));
                if (changed) {
                    CliOutput.err("jk assemble: wrote [application] " + overrideLabel + " = true to "
                            + PathDisplay.styledRaw(toml));
                } else {
                    CliOutput.err("jk assemble: jk.toml already has " + overrideLabel + " = true");
                }
            } catch (IOException e) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Assemble", e.getMessage());
                return Exit.SOFTWARE;
            }
        }

        if (!oneOff) {
            var info = BuildCommand.projectInfoOrNull(dir);
            if (info == null || !info.assembly()) {
                CliOutput.err("""
                        jk assemble: no bundled artifact is configured — pick one:

                          One-off (this run only):
                            jk assemble --fat
                            jk assemble --minified

                          Persist in jk.toml:
                            jk assemble --fat --write-config
                            jk assemble --minified --write-config

                          Or edit manually:
                            [application]
                            main = "your.Main"   # optional but usual for a runnable jar
                            assembly = true      # -all.jar, every dependency bundled
                            # minified = true    # -min.jar via R8, built beside -all.jar

                        Then re-run `jk assemble` (or `jk build`). See docs/features/packaging.md.
                        """.stripIndent());
                return Exit.CONFIG;
            }
            return build.run(in);
        }

        // One-off --fat/--minified: install override for this invocation only, then restore. A sticky
        // SessionContext.assemblyOverride leaked into later Jk.execute / engine requests in the same
        // JVM (tests, multi-command tools) and forced R8 package-jar on library projects without main.
        if (!writeConfig) {
            CliOutput.err("""
                    jk assemble: one-off packaging override — %s for this run only
                      (not written to jk.toml; action cache keys include the packaging mode)
                      make it permanent: jk assemble --%s --write-config
                    """.formatted(minified ? "minified (R8, beside the fat jar)" : "fat", overrideLabel)
                    .stripIndent()
                    .trim());
        }
        var previous = SessionContext.current();
        SessionContext.install(previous.withAssemblyOverride(overrideLabel));
        try {
            return build.run(in);
        } finally {
            SessionContext.install(previous);
        }
    }
}
