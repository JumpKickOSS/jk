// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.host.Guest;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.winhost.MachineSetup;
import cc.jumpkick.winhost.SetupMemo;
import cc.jumpkick.winhost.WindowsFromGuest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk self setup-windows} — in a WSL distro, install JumpKick for Windows into the person's
 * Windows account through {@code powershell.exe}, pinned to this JumpKick's version, and start its
 * engine, which then fronts this distro's engine at localhost:8910. The same install the offer after a
 * build makes; an install that succeeds, or finds one already there, answers that offer, so no build
 * asks again.
 */
public final class SelfSetupWindowsCommand implements CliCommand {

    /** What the command does on this host; the seams tests replace. */
    interface Host {
        Guest guest();

        /** Windows' {@code jk.exe} as this guest reaches it; null when Windows has none. */
        @Nullable
        Path windowsJk();

        MachineSetup.Result install(Consumer<String> progress);

        Path memoFile();
    }

    static final Host LIVE = new Host() {
        @Override
        public Guest guest() {
            return Guest.detect();
        }

        @Override
        public @Nullable Path windowsJk() {
            return WindowsFromGuest.windowsJk(WindowsFromGuest.INTEROP);
        }

        @Override
        public MachineSetup.Result install(Consumer<String> progress) {
            return WindowsFromGuest.installFor(JkVersion.VERSION, progress);
        }

        @Override
        public Path memoFile() {
            return SetupMemo.file(JkDirs.state());
        }
    };

    private final Host host;

    public SelfSetupWindowsCommand() {
        this(LIVE);
    }

    SelfSetupWindowsCommand(Host host) {
        this.host = host;
    }

    @Override
    public String name() {
        return "setup-windows";
    }

    @Override
    public String description() {
        return "Install JumpKick for Windows from a WSL distro";
    }

    @Override
    public int run(Invocation in) {
        Guest guest = host.guest();
        if (guest.kind() != Guest.Kind.WSL || !guest.interop()) {
            CommandWedge.printFail(
                    "Self",
                    "setup-windows runs in a WSL distro with Windows interop on; on Windows, install with install.ps1");
            return Exit.USAGE;
        }
        Path jk = host.windowsJk();
        if (jk != null) {
            remember(true);
            CommandWedge.printOk("Self", "JumpKick for Windows is already installed (" + jk + ")");
            return Exit.SUCCESS;
        }
        MachineSetup.Result r = host.install(line -> CliOutput.out("  " + line));
        if (r.ok()) {
            remember(true);
            CommandWedge.printOk("Self", r.line());
            return Exit.SUCCESS;
        }
        CommandWedge.printFail("Self", r.line());
        return Exit.FAILURE;
    }

    private void remember(boolean accepted) {
        try {
            WindowsFromGuest.remember(host.memoFile(), accepted, Clock.SYSTEM.millis());
        } catch (IOException e) {
            CliOutput.err("could not record the answer: " + e.getMessage());
        }
    }
}
