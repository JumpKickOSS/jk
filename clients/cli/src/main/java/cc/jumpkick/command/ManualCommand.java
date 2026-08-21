// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.docs.JkManual;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;

/**
 * {@code jk manual} — print the JumpKick playbook (markdown) for coding agents and new users. No
 * engine round-trip. MCP: {@code jk_manual} / {@code jk://manual}.
 */
public final class ManualCommand implements CliCommand {

    @Override
    public String name() {
        return "manual";
    }

    @Override
    public String description() {
        return "Print the JumpKick playbook for agents and new users";
    }

    @Override
    public int run(Invocation in) {
        System.out.print(JkManual.markdown());
        System.out.flush();
        return Exit.SUCCESS;
    }
}
