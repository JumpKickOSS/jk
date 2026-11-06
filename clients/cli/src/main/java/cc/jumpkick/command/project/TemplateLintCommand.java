// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.TemplateLintAck;
import cc.jumpkick.wire.protocol.TemplateLintRequest;
import java.io.IOException;
import java.util.List;

/**
 * {@code jk template lint [<dir>…]} — the catalogue lint {@code jk new} applies, over one template
 * directory or every {@code .jk-template.toml} under a tree; non-zero on any failure.
 */
public final class TemplateLintCommand implements CliCommand {

    private static final String WEDGE = "Template";

    @Override
    public String name() {
        return "lint";
    }

    @Override
    public String description() {
        return "Lint templates the way jk new's catalogue does";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("dir", Arity.ZERO_OR_MORE, "A template, or a tree of them (default .)"));
    }

    @Override
    public int run(Invocation in) {
        GlobalOptions global = GlobalOptions.from(in);
        TemplateLintAck ack;
        try {
            ack = EngineClient.templateLint(
                    EnginePaths.current(),
                    new TemplateLintRequest(global.workingDir().toString(), in.positionals()));
        } catch (IOException e) {
            CommandWedge.printFail(WEDGE, e.getMessage());
            return Exit.SOFTWARE;
        }
        if (ack.error() != null) {
            CommandWedge.printFail(WEDGE, ack.error());
            return Exit.FAILURE;
        }
        for (String failure : ack.failures()) CliOutput.err(failure);
        String templates = ack.checked() == 1 ? "1 template" : ack.checked() + " templates";
        if (ack.failures().isEmpty()) {
            CommandWedge.printOk(WEDGE, templates + " pass lint");
            return Exit.SUCCESS;
        }
        int failed = ack.failures().size();
        CommandWedge.printFail(WEDGE, (failed == 1 ? "1 failure" : failed + " failures") + " in " + templates);
        return Exit.FAILURE;
    }
}
