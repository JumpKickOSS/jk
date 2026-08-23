// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.OpenBrowser;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.terminal.Style;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * {@code jk web} — ensure the engine is up, print the authenticated dashboard URL (OSC-8 clickable
 * link), and open it in the default browser ({@code $BROWSER}, else {@code open}/{@code start}/
 * {@code xdg-open}). The URL carries {@code #t=<token>} when the owner-only token file is readable
 * so the SPA can bootstrap auth (see docs/contributors/webclient.md).
 */
public final class WebCommand implements CliCommand {

    @Override
    public String name() {
        return "web";
    }

    @Override
    public String description() {
        return "Open the engine dashboard in a browser";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Print the URL only; do not open a browser", "--no-open"));
    }

    @Override
    public int run(Invocation in) {
        boolean noOpen = in.isSet("no-open");
        EnginePaths.Paths paths = EnginePaths.current();
        try {
            EngineClient.ensureRunning(paths, Jk.VERSION);
        } catch (IOException e) {
            CommandWedge.printFail("Web", e.getMessage());
            return Exit.SOFTWARE;
        }

        EngineClient.Status status =
                EngineClient.status(EnginePaths.activeSocket(paths)).orElse(null);
        if (status == null) {
            CommandWedge.printFail("Web", "Engine did not answer after start");
            return Exit.SOFTWARE;
        }
        if (status.httpUrl() == null || status.httpUrl().isBlank()) {
            String why = status.httpError() != null
                    ? "HTTP dashboard failed to start (" + status.httpError() + ")"
                    : "HTTP dashboard is disabled — set [http] enabled = true in config";
            CommandWedge.printFail("Web", why);
            return Exit.FAILURE;
        }

        String url = tokenizedUrl(status.httpUrl(), paths);
        Theme t = Theme.active();
        CommandWedge.envelopeStart();
        CliOutput.out(JkWedge.chipLine(Glyphs.PLAY, "Web", GlobalConfig.nerdFont(), "JumpKick Web Interface"));
        CliOutput.out("");
        CliOutput.out(Theme.colorize("  Open the URL below if a browser did not launch:", Style.EMPTY.italic()));
        // OSC-8 hyperlink + path color — same treatment as `jk engine status` Web UI line.
        String visible = Theme.colorize(url, t.path());
        CliOutput.out("  " + Ansi.hyperlink(url, visible));

        if (!noOpen) {
            boolean opened = OpenBrowser.open(url);
            if (opened) {
                CliOutput.out("");
                CliOutput.out(Theme.colorize("  Opening in your browser…", t.darkGray()));
            } else {
                CliOutput.out("");
                CliOutput.out(Theme.colorize("  Could not launch a browser — copy the URL above.", t.warning()));
            }
        }
        return Exit.SUCCESS;
    }

    /**
     * Dashboard URL with {@code #t=<token>} when the owner-only token file is readable (SPA bootstrap).
     * Package-visible for tests.
     */
    static String tokenizedUrl(String httpUrl, EnginePaths.Paths paths) {
        if (httpUrl == null || httpUrl.isBlank()) return "";
        try {
            String token = Files.readString(paths.httpToken()).trim();
            if (!token.isEmpty()) return httpUrl + "#t=" + token;
        } catch (IOException e) {
            // plain URL still serves the shell; mutations need a token
        }
        return httpUrl;
    }
}
