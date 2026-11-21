// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.List;

/**
 * A released command that now lives under another name: hidden from help, it says the new name on
 * stderr, then runs the new command with the same arguments.
 */
final class MovedCommand implements CliCommand {

    private final String name;
    private final String was;
    private final String now;
    private final CliCommand target;

    /** {@code was} (say {@code jk graph callers}) answered by {@code target}, spelled {@code now}. */
    MovedCommand(String name, String was, String now, CliCommand target) {
        this.name = name;
        this.was = was;
        this.now = now;
        this.target = target;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "Now " + now;
    }

    @Override
    public boolean hidden() {
        return true;
    }

    @Override
    public List<Opt> options() {
        return target.options();
    }

    @Override
    public List<Param> parameters() {
        return target.parameters();
    }

    @Override
    public MachineOutput machineOutput() {
        return target.machineOutput();
    }

    @Override
    public int run(Invocation in) throws Exception {
        CommandWedge.printChipErr(Glyphs.BANG, "jk", notice());
        return target.run(in);
    }

    /** {@code jk graph callers is now jk code usages}. */
    String notice() {
        return was + " is now " + now;
    }
}
