// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.config.UiAsk;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.ProcessEnv;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What {@code [ui] ask} does when a decision reaches a host that can ask neither by form nor by
 * link: a desktop notification carrying the decision's dashboard link ({@code notify-send} on
 * Linux, {@code osascript} on macOS), the browser opened on it, or nothing. Best effort: a missing
 * tool is reported, never thrown, since the tool result already carries the link.
 */
public final class DecisionAlerts {

    /** The notification's title. */
    public static final String TITLE = "jk needs you";

    /** Starts {@code argv}; true when a process started. */
    @FunctionalInterface
    public interface Launcher {
        boolean launch(List<String> argv);
    }

    private final Launcher launcher;
    private final String os;
    private final Function<String, @Nullable String> env;

    public DecisionAlerts() {
        this(DecisionAlerts::start, Os.name(), ProcessEnv::get);
    }

    public DecisionAlerts(Launcher launcher, String os, Function<String, @Nullable String> env) {
        this.launcher = launcher;
        this.os = os;
        this.env = env;
    }

    /** Does what {@code mode} says for {@code d}, whose dashboard link is {@code link}; answers what happened. */
    public String raise(UiAsk mode, Decision d, String link) {
        return switch (mode) {
            case QUIET -> "quiet: the link is above";
            case OPEN -> run(openCommand(link)) ? "opened the dashboard" : "could not open a browser";
            case NOTIFY ->
                run(notifyCommand(TITLE, body(d), link)) ? "sent a desktop notification" : "could not notify";
        };
    }

    /** The notification's text: the question, and how to answer a trust-changing one. */
    static String body(Decision d) {
        String how = d.trust() ? "Accept it in a terminal: " + d.command() : "Answer it in the dashboard.";
        return d.question() + "\n" + how;
    }

    private boolean run(List<String> argv) {
        return !argv.isEmpty() && launcher.launch(argv);
    }

    /** The argv that shows {@code title} and {@code body} with {@code link} under it; empty off Linux and macOS. */
    List<String> notifyCommand(String title, String body, String link) {
        String text = body + "\n" + link;
        String name = os.toLowerCase(Locale.ROOT);
        if (name.contains("mac")) {
            return List.of(
                    "osascript", "-e", "display notification " + appleQuote(text) + " with title " + appleQuote(title));
        }
        if (name.contains("win")) return List.of();
        return List.of("notify-send", "--app-name=jk", "--icon=dialog-question", title, text);
    }

    /** The argv that opens {@code link}: {@code $BROWSER} when set, else the platform's opener. */
    List<String> openCommand(String link) {
        String browser = env.apply("BROWSER");
        if (browser != null && !browser.isBlank()) {
            List<String> argv = new ArrayList<>(List.of(browser.strip().split("\\s+")));
            argv.add(link);
            return argv;
        }
        String name = os.toLowerCase(Locale.ROOT);
        if (name.contains("mac")) return List.of("open", link);
        // rundll32 does no shell parsing, so a link with '&' cannot run a command.
        if (name.contains("win")) return List.of("rundll32", "url.dll,FileProtocolHandler", link);
        return List.of("xdg-open", link);
    }

    /** {@code s} as an AppleScript string literal. */
    private static String appleQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean start(List<String> argv) {
        try {
            new ProcessBuilder(argv)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
