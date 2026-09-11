// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import cc.jumpkick.ide.IdeTarget;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.util.EnumSet;
import java.util.List;

/**
 * {@code jk export idea} — generate IntelliJ IDEA project files. Shares {@code jk ide --idea}'s
 * behavior exactly; provided under {@code export} so the three target systems (gradle / maven /
 * idea) live together.
 */
public final class ExportIdeaCommand implements CliCommand {

    private final IdeCommand delegate = new IdeCommand(EnumSet.of(IdeTarget.IDEA));

    @Override
    public String name() {
        return "idea";
    }

    @Override
    public String description() {
        return "Generate IntelliJ IDEA project files + .bsp/ (jk ide --idea)";
    }

    @Override
    public List<Opt> options() {
        return delegate.options();
    }

    /** Dispatch consults the command it resolved, so an alias has to answer for its delegate. */
    @Override
    public boolean scriptMode(Invocation in) {
        return delegate.scriptMode(in);
    }

    @Override
    public int run(Invocation in) throws Exception {
        return delegate.run(in);
    }
}
