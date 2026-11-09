// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.desktop.DesktopIdentity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk self desktop} — register the name and icon JumpKick's desktop notifications carry: the
 * Start-menu shortcut on Windows, the desktop entry on Linux ({@link DesktopIdentity}). The
 * installers run it for the default home; {@code --remove} takes it all away again, the step an
 * uninstall runs.
 */
public final class SelfDesktopCommand implements CliCommand {

    @Override
    public String name() {
        return "desktop";
    }

    @Override
    public String description() {
        return "Register the notifications' name and icon with the desktop";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Remove the registration and its icon.", "-d", "--remove"));
    }

    @Override
    public int run(Invocation in) {
        DesktopIdentity identity = DesktopIdentity.current();
        Path entry = identity.entry();
        try {
            if (in.isSet("remove")) {
                CommandWedge.printOk(
                        "Self", identity.unregister() ? "removed " + where(entry) : "nothing registered to remove");
                return Exit.SUCCESS;
            }
            if (entry == null) {
                CommandWedge.printOk("Self", "this desktop needs no registration");
                return Exit.SUCCESS;
            }
            identity.register();
            CommandWedge.printOk("Self", "notifications read " + DesktopIdentity.APP_NAME + ": registered " + entry);
            return Exit.SUCCESS;
        } catch (IOException e) {
            CommandWedge.printFail("Self", e.getMessage());
            return Exit.FAILURE;
        }
    }

    private static String where(@Nullable Path entry) {
        return entry == null ? "the icons" : entry + " and its icon";
    }
}
