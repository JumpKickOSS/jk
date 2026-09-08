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
import cc.jumpkick.terminal.TerminalSession;
import org.jspecify.annotations.Nullable;

/**
 * Everything {@code jk new} says out loud, and the one decision that makes it hard: <b>where</b> the
 * line goes. The wizard owns the controlling terminal, so its own success and failure lines have to
 * be written through that {@link TerminalSession} and flushed there — writing them to
 * {@link CliOutput} instead interleaves them with the region the wizard has not finished tearing
 * down, and the user sees a chip printed over a half-erased prompt. The flag and template paths have
 * no terminal session and go through {@link CommandWedge}. Both must produce the same line, so the
 * line is composed once here and the routing is the only thing that varies.
 */
final class NewChrome {

    private NewChrome() {}

    /**
     * {@code ‼ The group:name project already exists in this directory.} plus the failure chip.
     * {@code terminal} is the wizard's session when the wizard is what discovered the collision, and
     * null on the flag / template paths.
     */
    static void projectExists(String coord, boolean isModule, boolean isInit, @Nullable TerminalSession terminal) {
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

        if (terminal != null) {
            var writer = terminal.ttyOut();
            CommandWedge.markEnvelopeStarted();
            writer.println(warnLine);
            writer.println(chipLine);
            writer.flush();
        } else {
            CommandWedge.envelopeStartErr();
            CliOutput.err(warnLine);
            CliOutput.err(chipLine);
        }
    }

    /** Same styling as {@link #projectExists} for the no-JDK case. */
    static void noJdks() {
        var warn = Theme.active().warning();
        var label = Theme.active().activeStep();
        var body = Theme.active().normalGray();
        CommandWedge.envelopeStartErr();
        CliOutput.err(Theme.colorize(Glyphs.BANG, warn)
                + " "
                + Theme.colorize("Jk", label)
                + Theme.colorize(": No JDKs found on this system. Run ", body)
                + Theme.colorize("jk jdk install", warn)
                + Theme.colorize(" first, then re-run ", body)
                + Theme.colorize("jk new", warn)
                + Theme.colorize(".", body));
    }

    /**
     * The success chip. {@code terminal} routes it through the wizard's session (which already
     * opened the envelope — leading blank + closing spacer); null prints it as a plain wedge.
     * {@code parentProject} is the enclosing project's display name when the new project was
     * registered as one of its modules, else null.
     */
    static void created(
            NewInputs inputs, @Nullable String parentProject, boolean isInit, @Nullable TerminalSession terminal) {
        String line = successLine(inputs, parentProject, isInit);
        if (terminal == null) {
            CommandWedge.printLine(line);
            return;
        }
        var writer = terminal.ttyOut();
        CommandWedge.markEnvelopeStarted();
        writer.println(line);
        writer.flush();
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
