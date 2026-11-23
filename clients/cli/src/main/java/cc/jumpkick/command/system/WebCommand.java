// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static java.util.Objects.requireNonNullElse;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.DashboardToken;
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
import cc.jumpkick.model.command.MachineOutput;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.Style;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk web} — ensure the engine is up, print the dashboard URL (OSC-8 clickable link), and
 * open it in the default browser ({@code $BROWSER}, else {@code open}/{@code start}/{@code
 * xdg-open}). The browser is handed the URL signed with {@code #t=<token>} so the SPA can bootstrap
 * auth (see docs/contributors/webclient.md); the printed URL carries it only at a person's
 * interactive terminal ({@link DashboardToken}).
 *
 * <p>{@code -O jsonl} prints one {@code {url, engineId}} object instead and opens nothing; {@code
 * --with-token} adds {@code token}: how an editor extension finds and authenticates to the engine
 * this home's {@code jk} uses, without reading the engine's state directory itself. Only that
 * explicit ask prints the token in machine output.
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

    /** {@code -O jsonl}: the connect line the VS Code extension reads. */
    @Override
    public MachineOutput machineOutput() {
        return MachineOutput.JSONL;
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Print the URL only; do not open a browser", "-n", "--no-open"),
                Opt.flag("With -O jsonl: include the engine's bearer token (for an editor)", "-T", "--with-token"));
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

        EngineProbe.Status status = answer(EnginePaths.activeSocket(paths), EngineProbe.Patience.DEFAULT);
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
            String token = in.isSet("with-token") ? DashboardToken.token(paths) : null;
            CliOutput.machine(connectJson(status.httpUrl(), token, status.engineId()));
            return Exit.SUCCESS;
        }

        GlobalOptions global = GlobalOptions.from(in);
        String url = printedUrl(status.httpUrl(), DashboardToken.mayPrint(global), paths);
        Theme t = Theme.active();
        CommandWedge.envelopeStart();
        CliOutput.out(JkWedge.chipLine(Glyphs.PLAY, "Web", GlobalConfig.nerdFont(), "JumpKick Web Interface"));
        CliOutput.out("");
        CliOutput.out(Theme.paint("  Open the URL below if a browser did not launch:", Style.EMPTY.italic()));
        // OSC-8 hyperlink + path color — same treatment as `jk engine status` Web UI line.
        String visible = Theme.paint(url, t.path());
        CliOutput.out("  " + Osc.hyperlink(url, visible));

        if (!DashboardToken.mayPrint(global)) {
            CliOutput.out(Theme.paint(
                    "  The token is left out here (not an interactive terminal); a browser that has signed in"
                            + " opens it, else run `jk web` at a terminal.",
                    t.darkGray()));
        }

        if (!noOpen) {
            boolean opened = OpenBrowser.open(requireNonNullElse(DashboardToken.signed(status.httpUrl(), paths), url));
            if (opened) {
                CliOutput.out("");
                CliOutput.out(Theme.paint("  Opening in your browser…", t.darkGray()));
            } else {
                CliOutput.out("");
                CommandWedge.printErrLine(() -> Theme.paint(
                        "  Could not open a browser; copy the URL above.",
                        Theme.active().warning()));
            }
        }
        return Exit.SUCCESS;
    }

    /**
     * The status of the engine just ensured, giving a silent one {@code patience}. On a loaded host
     * an engine that has only now started, or is in a collection pause, can take longer than one
     * probe's reply timeout to answer; the ensure has already seen it, so the silence is a wait,
     * not an absence. Null when nothing listens or the engine stays silent throughout.
     * Package-visible for tests.
     */
    static EngineProbe.@Nullable Status answer(Path socket, EngineProbe.Patience patience) {
        return EngineProbe.statusPatiently(socket, patience).orElse(null);
    }

    private static int fail(boolean json, @Nullable String why, int exit) {
        if (json) CliOutput.machine(JsonFields.object().string("error", why, "").finish());
        else CommandWedge.printFail("Web", why);
        return exit;
    }

    /**
     * The {@code -O jsonl} line: the engine's HTTP base URL, its identity id and, only when {@code
     * --with-token} asked, its bearer token ({@code ""} when the owner-only token file cannot be
     * read). Package-visible for tests.
     */
    static String connectJson(String httpUrl, @Nullable String token, @Nullable String engineId) {
        JsonFields fields = JsonFields.object().string("url", httpUrl);
        if (token != null) fields.string("token", token);
        return fields.string("engineId", engineId, "").finish();
    }

    /**
     * The dashboard URL as printed: signed with {@code #t=<token>} when {@code signed} and the
     * owner-only token file is readable, else bare. Package-visible for tests.
     */
    static String printedUrl(String httpUrl, boolean signed, EnginePaths.Paths paths) {
        if (httpUrl == null || httpUrl.isBlank()) return "";
        // A plain URL still serves the shell; mutations need a token the browser already holds.
        return signed ? requireNonNullElse(DashboardToken.signed(httpUrl, paths), httpUrl) : httpUrl;
    }
}
