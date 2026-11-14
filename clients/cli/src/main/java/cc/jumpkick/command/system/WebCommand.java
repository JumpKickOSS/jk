// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.OpenBrowser;
import cc.jumpkick.cli.api.Osc;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk web} — ensure the engine is up, print the authenticated dashboard URL (OSC-8 clickable
 * link), and open it in the default browser ({@code $BROWSER}, else {@code open}/{@code start}/
 * {@code xdg-open}). The URL carries {@code #t=<token>} when the owner-only token file is readable
 * so the SPA can bootstrap auth (see docs/contributors/webclient.md).
 *
 * <p>{@code -O jsonl} prints one {@code {url, token, engineId}} object instead and opens nothing:
 * how an editor extension finds and authenticates to the engine this home's {@code jk} uses,
 * without reading the engine's state directory itself.
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
        return List.of(Opt.flag("Print the URL only; do not open a browser", "-n", "--no-open"));
    }

    @Override
    public int run(Invocation in) {
        boolean json = GlobalOptions.outputIsJson(in);
        boolean noOpen = json || in.isSet("no-open");
        EnginePaths.Paths paths = EnginePaths.current();
        try {
            EngineClient.ensured(paths);
        } catch (IOException e) {
            return fail(json, e.getMessage(), Exit.SOFTWARE);
        }

        EngineProbe.Status status =
                EngineProbe.status(EnginePaths.activeSocket(paths)).orElse(null);
        if (status == null) {
            return fail(json, "Engine did not answer after start", Exit.SOFTWARE);
        }
        if (status.httpUrl() == null || status.httpUrl().isBlank()) {
            String why = status.httpError() != null
                    ? "HTTP dashboard failed to start (" + status.httpError() + ")"
                    : "HTTP dashboard is off; set [http] enabled = true in config";
            return fail(json, why, Exit.FAILURE);
        }
        if (json) {
            CliOutput.out(connectJson(status.httpUrl(), token(paths), status.engineId()));
            return Exit.SUCCESS;
        }

        String url = tokenizedUrl(status.httpUrl(), paths);
        Theme t = Theme.active();
        CommandWedge.envelopeStart();
        CliOutput.out(JkWedge.chipLine(Glyphs.PLAY, "Web", GlobalConfig.nerdFont(), "JumpKick Web Interface"));
        CliOutput.out("");
        CliOutput.out(Theme.paint("  Open the URL below if a browser did not launch:", Style.EMPTY.italic()));
        // OSC-8 hyperlink + path color — same treatment as `jk engine status` Web UI line.
        String visible = Theme.paint(url, t.path());
        CliOutput.out("  " + Osc.hyperlink(url, visible));

        if (!noOpen) {
            boolean opened = OpenBrowser.open(url);
            if (opened) {
                CliOutput.out("");
                CliOutput.out(Theme.paint("  Opening in your browser…", t.darkGray()));
            } else {
                CliOutput.out("");
                CliOutput.out(Theme.paint("  Could not open a browser; copy the URL above.", t.warning()));
            }
        }
        return Exit.SUCCESS;
    }

    private static int fail(boolean json, @Nullable String why, int exit) {
        if (json) CliOutput.out(JsonFields.object().string("error", why, "").finish());
        else CommandWedge.printFail("Web", why);
        return exit;
    }

    /**
     * The {@code -O jsonl} line: the engine's HTTP base URL, its bearer token ({@code ""} when the
     * owner-only token file cannot be read) and its identity id. Package-visible for tests.
     */
    static String connectJson(String httpUrl, String token, @Nullable String engineId) {
        return JsonFields.object()
                .string("url", httpUrl)
                .string("token", token)
                .string("engineId", engineId, "")
                .finish();
    }

    /** The bearer token from the owner-only token file, {@code ""} when it cannot be read. */
    private static String token(EnginePaths.Paths paths) {
        try {
            return Files.readString(paths.httpToken()).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Dashboard URL with {@code #t=<token>} when the owner-only token file is readable (SPA bootstrap).
     * Package-visible for tests.
     */
    static String tokenizedUrl(String httpUrl, EnginePaths.Paths paths) {
        if (httpUrl == null || httpUrl.isBlank()) return "";
        String token = token(paths);
        // A plain URL still serves the shell; mutations need a token.
        return token.isEmpty() ? httpUrl : httpUrl + "#t=" + token;
    }
}
