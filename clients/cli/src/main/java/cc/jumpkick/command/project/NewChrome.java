// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.NerdFontCaps;

/** What {@code jk new} says when the target already holds a project. */
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
}
