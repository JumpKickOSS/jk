// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.CommonOpts;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.command.ide.IdeChrome;
import cc.jumpkick.command.ide.IdeGeneration;
import cc.jumpkick.command.ide.IdeGenerator;
import cc.jumpkick.command.ide.IdeModel;
import cc.jumpkick.command.ide.IdeSupport;
import cc.jumpkick.command.ide.IdeTarget;
import cc.jumpkick.command.ide.IntellijIdeGenerator;
import cc.jumpkick.command.ide.VscodeIdeGenerator;
import cc.jumpkick.host.Errors;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk ide} — generate IntelliJ + VS Code project files ({@code --idea}/{@code --vscode}
 * narrow to one) and always refresh {@code .bsp/jk.json} for BSP clients. Dependency sync is
 * engine-hosted; model + file generation stay client-side.
 */
public final class IdeCommand implements CliCommand {

    /** The generators, in a stable emit order. */
    private static final List<IdeGenerator> GENERATORS = List.of(new IntellijIdeGenerator(), new VscodeIdeGenerator());

    /** When non-null, the command runs exactly these targets and ignores the {@code --idea/--vscode} flags. */
    private final @Nullable Set<IdeTarget> forced;

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
        opts.add(Opt.flag("Print engine ide-model JSON only (no files).", "--print-model"));
        opts.add(Opt.value(
                        "<dir>",
                        "Override cache-tier directory (action outputs; not the artifact store). Default: $JK_CACHE_DIR or ~/.jk/cache.",
                        "--cache-dir")
                .hide());
        opts.add(CommonOpts.jdksDir());
        opts.add(
                Opt.value("<dir>", "Override the IDE config root for SDK registration (for tests).", "--ide-config-dir")
                        .hide());
        return opts;
    }

    /** {@code --print-model} is the IDE plugins' wire channel: raw JSON on stdout, byte-exact. */
    @Override
    public boolean scriptMode(Invocation in) {
        return in.has("print-model");
    }

    @Override
    public int run(Invocation in) throws Exception {
        // Machine path for IDE plugins: structured model without writing .iml/.vscode.
        if (in.has("print-model")) {
            return printModel(in);
        }

        Set<IdeTarget> targets = selectTargets(in);

        try (IdeChrome chrome = IdeChrome.start("Sync")) {
            IdeModel model;
            try {
                model = IdeSupport.build(in, chrome);
            } catch (IdeSupport.IdeException e) {
                // null message = already reported (e.g. EnsureFreshLock failure wedge)
                if (e.getMessage() != null && !e.getMessage().isBlank()) {
                    chrome.fail(e.getMessage());
                } else {
                    chrome.dismiss();
                }
                return e.code();
            }

            String rootName = model.rootName();
            for (IdeGenerator gen : GENERATORS) {
                if (!targets.contains(gen.target())) continue;
                chrome.phase(IdeChrome.phaseReady(gen.target().label(), rootName));
                try {
                    IdeGeneration result = gen.generate(model);
                    chrome.addDetails(result.details());
                } catch (IdeSupport.IdeException e) {
                    chrome.fail(Errors.text(e));
                    return e.code();
                } catch (Exception e) {
                    chrome.fail(
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName());
                    return 1;
                }
            }
            // BSP discovery is part of "IDE ready" — Metals / JetBrains BSP spawn via .bsp/jk.json.
            try {
                Path bsp = BspCommand.writeConnectionFile(model.wsRoot());
                chrome.phase(IdeChrome.bspWrote(bsp, model.wsRoot()));
            } catch (Exception e) {
                chrome.fail(
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return 1;
            }
            chrome.note(IdeChrome.restartNote());
            chrome.succeed(IdeChrome.projectReady(rootName));
            return 0;
        }
    }

    /**
     * Dump {@link cc.jumpkick.wire.protocol.IdeWireModel} JSON for IDE hosts. Reuses lock + sync
     * + engine model (same as file generators) but skips disk writes.
     */
    private static int printModel(Invocation in) throws Exception {
        try {
            var wire = IdeSupport.wireModel(in);
            // Raw wire JSON only — no TTY chrome (plugins parse stdout).
            CliOutput.out(wire.encode());
            return 0;
        } catch (IdeSupport.IdeException e) {
            if (e.getMessage() != null && !e.getMessage().isBlank()) {
                CommandWedge.printFail("IDE", e.getMessage());
            }
            return e.code();
        }
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
