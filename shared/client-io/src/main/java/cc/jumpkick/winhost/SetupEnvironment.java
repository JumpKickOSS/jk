// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.util.List;
import java.util.function.Consumer;

/**
 * A source of setup rows that install JumpKick into another environment (a WSL distro, Windows from a
 * guest). Such a row needs no elevation, so it never joins the UAC prompt; it runs after the person
 * answers and may take minutes, reporting a line per step.
 */
public interface SetupEnvironment {

    /** The rows to propose for {@code check}'s machine, each an {@link SetupChange.Kind#ENVIRONMENT} change. */
    List<SetupChange> rows(WindowsHostCheck check);

    /** True when {@code row} is one this source proposed. */
    boolean owns(SetupChange row);

    /** Installs {@code row}, handing each step's line to {@code progress}; answers how it ended. */
    MachineSetup.Result install(SetupChange row, Consumer<String> progress);
}
