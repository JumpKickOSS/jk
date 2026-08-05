// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.command.ide.IdeGenerator;
import cc.jumpkick.command.ide.IdeModel;
import cc.jumpkick.command.ide.IdeSupport;
import cc.jumpkick.command.ide.IdeTarget;
import cc.jumpkick.command.ide.IntellijIdeGenerator;
import cc.jumpkick.command.ide.VscodeIdeGenerator;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * {@code jk ide} — generate IntelliJ + VS Code project files ({@code --idea}/{@code --vscode}
 * narrow to one) and always refresh {@code .bsp/jk.json} for BSP clients. Dependency sync is
 * engine-hosted; model + file generation stay client-side.
 */
public final class IdeCommand implements CliCommand {

    /** The generators, in a stable emit order. */
    private static final List<IdeGenerator> GENERATORS = List.of(new IntellijIdeGenerator(), new VscodeIdeGenerator());

    /** When non-null, the command runs exactly these targets and ignores the {@code --idea/--vscode} flags. */
    private final Set<IdeTarget> forced;

    public IdeCommand() {
        this.forced = null;
    }

    public IdeCommand(Set<IdeTarget> forced) {
        this.forced = forced;
    }

    @Override
    public String name() {
        return "ide";
    }

    @Override
    public String description() {
        return "Generate IDE project files (IntelliJ + VS Code) and .bsp/";
    }

    @Override
    public List<Opt> options() {
        List<Opt> opts = new ArrayList<>();
        if (forced == null) {
            opts.add(Opt.flag("Only generate IntelliJ IDEA files (.idea/ + *.iml).", "--idea"));
            opts.add(Opt.flag("Only generate VS Code files (.vscode/ + Eclipse metadata).", "--vscode"));
        }
        opts.add(Opt.value(
                        "<dir>",
                        "Override cache-tier directory (action outputs; not the artifact store). Default: $JK_CACHE_DIR or ~/.cache/jk.",
                        "--cache-dir")
                .hide());
        opts.add(Opt.value("<dir>", "Override the JDK install root (for tests).", "--jdks-dir")
                .hide());
        opts.add(
                Opt.value("<dir>", "Override the IDE config root for SDK registration (for tests).", "--ide-config-dir")
                        .hide());
        return opts;
    }

    @Override
    public int run(Invocation in) throws Exception {
        Set<IdeTarget> targets = selectTargets(in);

        IdeModel model;
        try {
            model = IdeSupport.build(in);
        } catch (IdeSupport.IdeException e) {
            // null message = already reported (e.g. EnsureFreshLock failure wedge)
            if (e.getMessage() != null && !e.getMessage().isBlank()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("IDE", e.getMessage()));
            }
            return e.code();
        }

        for (IdeGenerator gen : GENERATORS) {
            if (!targets.contains(gen.target())) continue;
            try {
                gen.generate(model);
            } catch (IdeSupport.IdeException e) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("IDE", e.getMessage()));
                return e.code();
            }
        }
        // BSP discovery is part of "IDE ready" — Metals / JetBrains BSP spawn via .bsp/jk.json.
        try {
            Path bsp = BspCommand.writeConnectionFile(model.wsRoot());
            cc.jumpkick.cli.tui.CommandWedge.printOk("BSP", "Wrote " + bsp);
        } catch (Exception e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "BSP", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return 1;
        }
        return 0;
    }

    /** Resolve which IDEs to generate: the forced set, else the flags, else both. */
    private Set<IdeTarget> selectTargets(Invocation in) {
        if (forced != null) return forced;
        boolean idea = in.has("idea");
        boolean vscode = in.has("vscode");
        if (idea && !vscode) return EnumSet.of(IdeTarget.IDEA);
        if (vscode && !idea) return EnumSet.of(IdeTarget.VSCODE);
        return EnumSet.allOf(IdeTarget.class);
    }
}
