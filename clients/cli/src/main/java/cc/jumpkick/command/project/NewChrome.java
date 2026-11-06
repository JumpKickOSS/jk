// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.scaffold.NewInputs;
import cc.jumpkick.terminal.Style;
import org.jspecify.annotations.Nullable;

/** What the blank scaffolder's {@code jk new} says: the existing-project failure and the success chip. */
final class NewChrome {

    private NewChrome() {}

    /** {@code ‼ The group:name project already exists in this directory.} plus the failure chip. */
    static void projectExists(String coord, boolean isModule, boolean isInit) {
        Theme t = Theme.active();
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        String noun = isModule ? "module" : "project";

        // Style the coord: group:name in coordGroup/coordName; bare name in coordName.
        int colon = coord.indexOf(':');
        String coordStyled = colon > 0
                ? Theme.colorize(coord.substring(0, colon), t.coordGroup())
                        + ":"
                        + Theme.colorize(coord.substring(colon + 1), t.coordName())
                : Theme.colorize(coord, t.coordName());

        String warnLine = Theme.colorize(Glyphs.BANG, t.warning()) + " The " + coordStyled + " " + noun
                + " already exists in this directory.";

        // Use chipLine (not failureLine) — failureLine auto-prepends "Failed to {command}" which
        // would double up if the tail also starts with "Failed to".
        String chipCommand = isModule ? "New Module" : "New Project";
        String bareName = colon > 0 ? coord.substring(colon + 1) : coord;
        String failTail = "Failed to " + (isInit ? "initialize" : "create") + " " + noun + " " + bareName
                + ". Project already exists.";
        String chipLine = JkWedge.chipLine(Glyphs.CROSS, chipCommand, nerdFont, failTail);

        CommandWedge.envelopeStartErr();
        CliOutput.err(warnLine);
        CliOutput.err(chipLine);
    }

    /**
     * The success chip. {@code parentProject} is the enclosing project's display name when the new
     * project was registered as one of its modules, else null.
     */
    static void created(NewInputs inputs, @Nullable String parentProject, boolean isInit) {
        CommandWedge.printLine(successLine(inputs, parentProject, isInit));
    }

    private static String successLine(NewInputs inputs, @Nullable String parentProject, boolean isInit) {
        NerdFontCaps nerdFont = GlobalConfig.nerdFont();
        Style accent = Theme.active().brightCyan().bold();
        if (parentProject != null) {
            String message = "New module "
                    + Theme.paint(inputs.name(), accent)
                    + Theme.colorize(" added to project ", Theme.active().normalGray())
                    + Theme.colorize(parentProject, accent);
            return JkWedge.chipLine(Glyphs.CHECK, "New Module", nerdFont, message);
        }
        String chipCommand = isInit ? "Init" : "New Project";
        String action = isInit ? "Initialized" : "Created new";
        String message = action + " project " + Theme.paint(inputs.name(), accent);
        return JkWedge.chipLine(Glyphs.CHECK, chipCommand, nerdFont, message);
    }
}
