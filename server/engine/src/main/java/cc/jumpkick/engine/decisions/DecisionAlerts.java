// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.UiAsk;
import cc.jumpkick.desktop.DesktopIdentity;
import cc.jumpkick.desktop.PowerShell;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.Processes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What {@code [ui] ask} does when a decision reaches a host that can ask neither by form nor by
 * link: a desktop notification carrying the question and the decision's dashboard link, the browser
 * opened on it, or nothing. On Windows the notification is a toast whose click opens the link; on
 * Linux a {@code notify-send} that supports actions opens it on a click too; on macOS {@code
 * osascript} shows the link in the text. Windows and Linux notifications carry JumpKick's name and
 * icon through its {@link DesktopIdentity}, registered before the first one when it is missing. A
 * notification still showing is withdrawn when its decision settles. Best effort: a missing tool is
 * reported, never thrown, since the tool result already carries the link.
 */
public final class DecisionAlerts implements AutoCloseable {

    /** The notification's title. */
    public static final String TITLE = "JumpKick needs you";

    /** The group every decision's toast is tagged under. */
    static final String TOAST_GROUP = DesktopIdentity.APP_NAME;

    /** The most of the body a toast carries, so the encoded command stays far under Windows' limit. */
    private static final int TOAST_TEXT_MAX = 1000;

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /** Starts processes; the seam tests replace. */
    @FunctionalInterface
    public interface Launcher {
        /** Starts {@code argv} with its output discarded; true when a process started. */
        boolean launch(List<String> argv);

        /** Runs {@code argv} to its end and answers what it printed; null when it could not. */
        default @Nullable String output(List<String> argv) {
            return null;
        }

        /**
         * Starts {@code argv} and hands each line it prints to {@code lines}, then runs {@code exited}
         * once it ends; null when no process started.
         */
        default @Nullable Watch watch(List<String> argv, Consumer<String> lines, Runnable exited) {
            return launch(argv) ? () -> {} : null;
        }
    }

    /** A process {@link Launcher#watch} started. */
    @FunctionalInterface
    public interface Watch {
        /** Ends the process if it still runs. */
        void stop();
    }

    /** A Linux notification waiting for its click: the process and, once it printed it, the server's id. */
    private static final class Waiting {
        volatile @Nullable Watch watch;
        volatile @Nullable String id;
    }

    private final Launcher launcher;
    private final String os;
    private final Function<String, @Nullable String> env;
    private final DesktopIdentity identity;
    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();
    private final Set<String> toasts = ConcurrentHashMap.newKeySet();
    private volatile @Nullable Boolean actions;

    public DecisionAlerts() {
        this(new RealLauncher(), Os.name(), ProcessEnv::get, DesktopIdentity.current());
    }

    public DecisionAlerts(
            Launcher launcher, String os, Function<String, @Nullable String> env, DesktopIdentity identity) {
        this.launcher = launcher;
        this.os = os;
        this.env = env;
        this.identity = identity;
    }

    /** Does what {@code mode} says for {@code d}, whose dashboard link is {@code link}; answers what happened. */
    public String raise(UiAsk mode, Decision d, String link) {
        return switch (mode) {
            case QUIET -> "quiet: the link is above";
            case OPEN -> run(openCommand(link)) ? "opened the dashboard" : "could not open a browser";
            case NOTIFY -> notify(d, link) ? "sent a desktop notification" : "could not notify";
        };
    }

    /** Takes back the notification {@link #raise} showed for decision {@code id}, if it still shows. */
    public void withdraw(String id) {
        if (toasts.remove(id)) run(toastRemoveCommand(id));
        Waiting w = waiting.remove(id);
        if (w == null) return;
        String shown = w.id;
        if (shown != null) run(closeCommand(shown));
        Watch watch = w.watch;
        if (watch != null) watch.stop();
    }

    /** Ends every notification process still waiting; the notifications' decisions outlive this engine. */
    @Override
    public void close() {
        for (String id : List.copyOf(waiting.keySet())) {
            Waiting w = waiting.remove(id);
            Watch watch = w == null ? null : w.watch;
            if (watch != null) watch.stop();
        }
    }

    /** The notification's text: the question, and where to answer it. */
    static String body(Decision d) {
        return d.question() + "\nAnswer it in the dashboard.";
    }

    private boolean notify(Decision d, String link) {
        String body = body(d);
        if (Os.isDarwin(os)) return run(appleCommand(TITLE, body, link));
        identity.ensure();
        if (Os.isWindows(os)) {
            if (!run(toastCommand(d.id(), TITLE, body, link))) return false;
            toasts.add(d.id());
            return true;
        }
        Path icon = identity.png();
        if (!notifySendActions()) return run(notifySendCommand(TITLE, body, link, icon));
        Waiting w = new Waiting();
        waiting.put(d.id(), w);
        Watch watch = launcher.watch(
                notifySendActionCommand(TITLE, body, link, icon),
                line -> {
                    String s = line.strip();
                    if (s.equals("default")) run(openCommand(link));
                    else if (w.id == null && s.matches("\\d+")) w.id = s;
                },
                () -> waiting.remove(d.id(), w));
        if (watch == null) {
            waiting.remove(d.id(), w);
            return false;
        }
        w.watch = watch;
        // Withdrawn or ended before the handle was stored: stop it here instead.
        if (waiting.get(d.id()) != w) watch.stop();
        return true;
    }

    private boolean run(List<String> argv) {
        return !argv.isEmpty() && launcher.launch(argv);
    }

    /** Whether this host's {@code notify-send} takes {@code --action} and {@code --wait}; asked once. */
    private boolean notifySendActions() {
        Boolean known = actions;
        if (known == null) {
            known = actionsIn(launcher.output(List.of("notify-send", "--version")))
                    || contains(launcher.output(List.of("notify-send", "--help")), "--action");
            actions = known;
        }
        return known;
    }

    /** True when {@code version}, {@code notify-send --version}'s output, names 0.7.10 or later. */
    static boolean actionsIn(@Nullable String version) {
        if (version == null) return false;
        Matcher m = VERSION.matcher(version);
        if (!m.find()) return false;
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        if (major != 0) return major > 0;
        return minor > 7 || (minor == 7 && patch >= 10);
    }

    private static boolean contains(@Nullable String text, String what) {
        return text != null && text.contains(what);
    }

    /** {@code osascript} showing {@code body} with {@code link} under it. */
    static List<String> appleCommand(String title, String body, String link) {
        String text = body + "\n" + link;
        return List.of(
                "osascript", "-e", "display notification " + appleQuote(text) + " with title " + appleQuote(title));
    }

    /**
     * {@code notify-send} showing {@code body} with {@code link} under it: no click-through. The
     * desktop-entry hint is what GNOME names and badges the notification by.
     */
    static List<String> notifySendCommand(String title, String body, String link, Path icon) {
        return List.of(
                "notify-send",
                "--app-name=" + DesktopIdentity.APP_NAME,
                "--icon=" + icon,
                "--hint=string:desktop-entry:" + DesktopIdentity.DESKTOP_ENTRY,
                title,
                body + "\n" + link);
    }

    /**
     * {@code notify-send} that prints the notification's id, waits, and prints {@code default} when
     * the notification is clicked. The text keeps the link for a server that shows no actions.
     */
    static List<String> notifySendActionCommand(String title, String body, String link, Path icon) {
        return List.of(
                "notify-send",
                "--app-name=" + DesktopIdentity.APP_NAME,
                "--icon=" + icon,
                "--hint=string:desktop-entry:" + DesktopIdentity.DESKTOP_ENTRY,
                "--action=default=Open",
                "--print-id",
                "--wait",
                title,
                body + "\n" + link);
    }

    /** Closes the notification server's notification {@code id}. */
    static List<String> closeCommand(String id) {
        return List.of(
                "gdbus",
                "call",
                "--session",
                "--dest=org.freedesktop.Notifications",
                "--object-path=/org/freedesktop/Notifications",
                "--method=org.freedesktop.Notifications.CloseNotification",
                id);
    }

    /**
     * PowerShell showing a toast tagged {@code id}, under JumpKick's AppUserModelID, whose click opens
     * {@code link}.
     */
    static List<String> toastCommand(String id, String title, String body, String link) {
        String xml = "<toast activationType=\"protocol\" launch=\"" + xmlEscape(link) + "\">"
                + "<visual><binding template=\"ToastGeneric\">"
                + "<text>" + xmlEscape(title) + "</text>"
                + "<text>" + xmlEscape(clip(body)) + "</text>"
                + "</binding></visual></toast>";
        String script = TOAST_PRELUDE
                + "[void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType=WindowsRuntime]\n"
                + "$x = New-Object Windows.Data.Xml.Dom.XmlDocument\n"
                + "$x.LoadXml(" + PowerShell.literal(xml) + ")\n"
                + "$t = New-Object Windows.UI.Notifications.ToastNotification $x\n"
                + "$t.Tag = " + PowerShell.literal(id) + "\n"
                + "$t.Group = " + PowerShell.literal(TOAST_GROUP) + "\n"
                + "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("
                + PowerShell.literal(DesktopIdentity.AUMID) + ").Show($t)\n";
        return PowerShell.command(script);
    }

    /** PowerShell removing the toast tagged {@code id} from the notification center. */
    static List<String> toastRemoveCommand(String id) {
        String script = TOAST_PRELUDE
                + "[Windows.UI.Notifications.ToastNotificationManager]::History.Remove(" + PowerShell.literal(id)
                + ", " + PowerShell.literal(TOAST_GROUP) + ", " + PowerShell.literal(DesktopIdentity.AUMID) + ")\n";
        return PowerShell.command(script);
    }

    private static final String TOAST_PRELUDE = PowerShell.PRELUDE
            + "[void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime]\n";

    /** The argv that opens {@code link}: {@code $BROWSER} when set, else the platform's opener. */
    List<String> openCommand(String link) {
        String browser = env.apply("BROWSER");
        if (browser != null && !browser.isBlank()) {
            List<String> argv = new ArrayList<>(List.of(browser.strip().split("\\s+")));
            argv.add(link);
            return argv;
        }
        if (Os.isDarwin(os)) return List.of("open", link);
        // rundll32 does no shell parsing, so a link with '&' cannot run a command.
        if (Os.isWindows(os)) return List.of("rundll32", "url.dll,FileProtocolHandler", link);
        return List.of("xdg-open", link);
    }

    /** {@code s} as an AppleScript string literal. */
    private static String appleQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String clip(String s) {
        return s.length() <= TOAST_TEXT_MAX ? s : s.substring(0, TOAST_TEXT_MAX - 1) + "…";
    }

    /** {@code s} as XML character data or an attribute value; characters XML cannot hold are dropped. */
    static String xmlEscape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0xFFFE && c != 0xFFFF)) {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.toString();
    }

    /** Real processes: output discarded, read briefly, or watched on a daemon thread. */
    static final class RealLauncher implements Launcher {

        private static final Duration OUTPUT_WAIT = Duration.ofSeconds(3);

        @Override
        public boolean launch(List<String> argv) {
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

        @Override
        public @Nullable String output(List<String> argv) {
            try {
                Processes.Captured c = Processes.capture(argv, OUTPUT_WAIT);
                return c.timedOut() ? null : c.output();
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public @Nullable Watch watch(List<String> argv, Consumer<String> lines, Runnable exited) {
            Process p;
            try {
                p = new ProcessBuilder(argv).redirectError(Redirect.DISCARD).start();
            } catch (IOException | RuntimeException e) {
                return null;
            }
            SessionContext.startPlatform("jk-decision-alert", () -> {
                try (BufferedReader in =
                        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = in.readLine()) != null; ) lines.accept(line);
                } catch (IOException | RuntimeException ignored) {
                    // The process ended or was stopped; nothing more to read.
                } finally {
                    exited.run();
                }
            });
            return p::destroy;
        }
    }
}
