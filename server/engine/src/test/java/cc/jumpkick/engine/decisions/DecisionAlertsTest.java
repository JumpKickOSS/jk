// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.UiAsk;
import cc.jumpkick.form.Form;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * {@code [ui] ask}: the notification carries the question and the deep link, a click opens it where
 * the platform allows, the notification goes when its decision settles; open opens the link, quiet
 * does nothing.
 */
class DecisionAlertsTest {

    private static final String LINK = "http://127.0.0.1:8910/#decision/abc?t=T";

    /** Records what it is asked to start; answers {@link #outputs} and keeps every watch. */
    private static final class Recorder implements DecisionAlerts.Launcher {
        final List<List<String>> launched = new ArrayList<>();
        final Map<List<String>, String> outputs = new HashMap<>();
        final List<List<String>> asked = new ArrayList<>();
        final List<Watched> watches = new ArrayList<>();

        @Override
        public boolean launch(List<String> argv) {
            launched.add(argv);
            return true;
        }

        @Override
        public @Nullable String output(List<String> argv) {
            asked.add(argv);
            return outputs.get(argv);
        }

        @Override
        public DecisionAlerts.Watch watch(List<String> argv, Consumer<String> lines, Runnable exited) {
            Watched w = new Watched(argv, lines, exited);
            watches.add(w);
            return w;
        }
    }

    private record Watched(List<String> argv, Consumer<String> lines, Runnable exited, boolean[] stopped)
            implements DecisionAlerts.Watch {
        Watched(List<String> argv, Consumer<String> lines, Runnable exited) {
            this(argv, lines, exited, new boolean[1]);
        }

        @Override
        public void stop() {
            stopped[0] = true;
        }
    }

    private final Recorder launcher = new Recorder();

    private DecisionAlerts on(String os, Map<String, String> env) {
        return new DecisionAlerts(launcher, os, env::get);
    }

    private static Decision decision() {
        return decision("Clean jk's cache?");
    }

    private static Decision decision(String question) {
        Form form = DecisionForms.confirm("disk-clean", question, "Yes");
        return new Decision(
                "abc",
                "disk-clean",
                question,
                List.of(),
                "jk decide abc",
                form,
                null,
                null,
                null,
                null,
                1,
                2,
                Decision.State.OPEN,
                Map.of(),
                null,
                0);
    }

    @Test
    void an_old_notify_send_gets_a_text_notification_that_carries_the_deep_link() {
        launcher.outputs.put(List.of("notify-send", "--version"), "notify-send 0.7.9\n");
        DecisionAlerts alerts = on("Linux", Map.of());
        assertThat(alerts.raise(UiAsk.NOTIFY, decision(), LINK)).isEqualTo("sent a desktop notification");
        assertThat(launcher.watches).isEmpty();
        assertThat(launcher.launched).singleElement().satisfies(argv -> {
            assertThat(argv.subList(0, 4))
                    .containsExactly(
                            "notify-send", "--app-name=JumpKick", "--icon=dialog-question", DecisionAlerts.TITLE);
            assertThat(argv).noneMatch(a -> a.startsWith("--action") || a.equals("--wait"));
            assertThat(argv.getLast()).isEqualTo("Clean jk's cache?\nAnswer it in the dashboard.\n" + LINK);
        });

        alerts.raise(UiAsk.NOTIFY, decision(), LINK);
        assertThat(launcher.asked).as("support is asked once").hasSize(2);
    }

    @Test
    void a_notify_send_with_actions_waits_for_a_click_and_opens_the_deep_link() {
        launcher.outputs.put(List.of("notify-send", "--version"), "notify-send 0.8.3\n");
        DecisionAlerts alerts = on("Linux", Map.of());
        assertThat(alerts.raise(UiAsk.NOTIFY, decision(), LINK)).isEqualTo("sent a desktop notification");
        assertThat(launcher.watches).singleElement().satisfies(w -> {
            assertThat(w.argv())
                    .containsExactly(
                            "notify-send",
                            "--app-name=JumpKick",
                            "--icon=dialog-question",
                            "--action=default=Open",
                            "--print-id",
                            "--wait",
                            DecisionAlerts.TITLE,
                            "Clean jk's cache?\nAnswer it in the dashboard.\n" + LINK);
            w.lines().accept("17");
            assertThat(launcher.launched).isEmpty();
            w.lines().accept("default");
        });
        assertThat(launcher.launched).containsExactly(List.of("xdg-open", LINK));
    }

    @Test
    void a_notify_send_whose_help_names_action_counts_as_supporting_it() {
        launcher.outputs.put(List.of("notify-send", "--help"), "  -A, --action=[NAME=]Text...\n");
        on("Linux", Map.of()).raise(UiAsk.NOTIFY, decision(), LINK);
        assertThat(launcher.watches).hasSize(1);
        assertThat(DecisionAlerts.actionsIn("notify-send 0.7.10")).isTrue();
        assertThat(DecisionAlerts.actionsIn("notify-send 1.0")).isTrue();
        assertThat(DecisionAlerts.actionsIn("notify-send 0.7.9")).isFalse();
        assertThat(DecisionAlerts.actionsIn(null)).isFalse();
    }

    @Test
    void settling_closes_the_linux_notification_and_ends_its_process() {
        launcher.outputs.put(List.of("notify-send", "--version"), "notify-send 0.8.3\n");
        DecisionQueue queue = new DecisionQueue();
        DecisionAlerts alerts = on("Linux", Map.of());
        new Decisions(queue, id -> null).alerts(alerts);
        Decision d = queue.open(new DecisionQueue.Ask(
                "disk-clean",
                "Clean jk's cache?",
                List.of(),
                DecisionForms.confirm("disk-clean", "Clean jk's cache?", "Yes"),
                null,
                null,
                null,
                null));
        alerts.raise(UiAsk.NOTIFY, d, LINK);
        Watched w = launcher.watches.getFirst();
        w.lines().accept("42");

        queue.decline(d.id(), DecisionSurface.CLI);

        assertThat(w.stopped()[0]).isTrue();
        assertThat(launcher.launched).singleElement().satisfies(argv -> assertThat(argv)
                .startsWith("gdbus", "call", "--session")
                .contains("--method=org.freedesktop.Notifications.CloseNotification")
                .endsWith("42"));

        alerts.withdraw(d.id());
        assertThat(launcher.launched).as("withdrawn once").hasSize(1);
    }

    @Test
    void a_notification_dismissed_on_its_own_is_not_withdrawn_again_and_close_ends_the_rest() {
        launcher.outputs.put(List.of("notify-send", "--version"), "notify-send 0.8.3\n");
        DecisionAlerts alerts = on("Linux", Map.of());
        alerts.raise(UiAsk.NOTIFY, decision(), LINK);
        Watched dismissed = launcher.watches.getFirst();
        dismissed.exited().run();
        alerts.withdraw("abc");
        assertThat(dismissed.stopped()[0]).isFalse();
        assertThat(launcher.launched).isEmpty();

        alerts.raise(UiAsk.NOTIFY, decision(), LINK);
        alerts.close();
        assertThat(launcher.watches.getLast().stopped()[0]).isTrue();
    }

    @Test
    void on_macos_the_notification_text_is_quoted_for_applescript() {
        on("Mac OS X", Map.of()).raise(UiAsk.NOTIFY, decision(), LINK);
        assertThat(launcher.launched).singleElement().satisfies(argv -> {
            assertThat(argv.subList(0, 2)).containsExactly("osascript", "-e");
            assertThat(argv.get(2))
                    .startsWith("display notification \"Clean jk's cache?\nAnswer it in the dashboard.\n")
                    .contains(LINK)
                    .endsWith("with title \"" + DecisionAlerts.TITLE + "\"");
        });
    }

    @Test
    void on_windows_a_toast_opens_the_deep_link_and_is_tagged_by_the_decision() throws Exception {
        assertThat(on("Windows 11", Map.of()).raise(UiAsk.NOTIFY, decision(), LINK))
                .isEqualTo("sent a desktop notification");
        List<String> argv = launcher.launched.getFirst();
        assertThat(argv.subList(0, 4))
                .containsExactly("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand");
        String script = script(argv);
        assertThat(script)
                .contains("ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime")
                .contains("CreateToastNotifier(");
        List<String> values = embedded(script);
        assertThat(values).contains("abc", DecisionAlerts.APP_NAME, DecisionAlerts.POWERSHELL_AUMID);

        Element toast = toast(values);
        assertThat(toast.getAttribute("activationType")).isEqualTo("protocol");
        assertThat(toast.getAttribute("launch")).isEqualTo(LINK);
        NodeList texts = toast.getElementsByTagName("text");
        assertThat(texts.item(0).getTextContent()).isEqualTo(DecisionAlerts.TITLE);
        assertThat(texts.item(1).getTextContent()).isEqualTo("Clean jk's cache?\nAnswer it in the dashboard.");
    }

    @Test
    void hostile_question_text_cannot_leave_its_quoting_on_windows() throws Exception {
        String hostile = "'); Remove-Item -Recurse C:\\ #\u2019\" $(calc) `n </text><text>&x; ]]>";
        on("Windows 11", Map.of()).raise(UiAsk.NOTIFY, decision(hostile), LINK);
        String script = script(launcher.launched.getFirst());
        assertThat(script).doesNotContain("Remove-Item", "calc", "</text>");
        assertThat(script.replaceAll("'[A-Za-z0-9+/=]*'", "''"))
                .as("every literal in the script is base64")
                .doesNotContain("Remove", "\u2019", "$(");
        NodeList texts = toast(embedded(script)).getElementsByTagName("text");
        assertThat(texts.getLength()).isEqualTo(2);
        assertThat(texts.item(1).getTextContent()).startsWith(hostile + "\n");
    }

    @Test
    void settling_removes_the_windows_toast() throws Exception {
        DecisionAlerts alerts = on("Windows 11", Map.of());
        alerts.withdraw("abc");
        assertThat(launcher.launched).as("nothing shown, nothing to remove").isEmpty();

        alerts.raise(UiAsk.NOTIFY, decision(), LINK);
        alerts.withdraw("abc");
        alerts.withdraw("abc");
        assertThat(launcher.launched).hasSize(2);
        String script = script(launcher.launched.getLast());
        assertThat(script).contains("::History.Remove(");
        assertThat(embedded(script)).containsExactly("abc", DecisionAlerts.APP_NAME, DecisionAlerts.POWERSHELL_AUMID);
    }

    @Test
    void open_opens_the_browser_on_the_link_and_quiet_does_nothing() {
        on("Linux", Map.of()).raise(UiAsk.OPEN, decision(), LINK);
        on("Linux", Map.of("BROWSER", "firefox --new-tab")).raise(UiAsk.OPEN, decision(), LINK);
        assertThat(on("Linux", Map.of()).raise(UiAsk.QUIET, decision(), LINK)).startsWith("quiet");
        assertThat(launcher.launched).containsExactly(List.of("xdg-open", LINK), List.of("firefox", "--new-tab", LINK));
    }

    /** The PowerShell script {@code argv} runs. */
    private static String script(List<String> argv) {
        return new String(Base64.getDecoder().decode(argv.getLast()), StandardCharsets.UTF_16LE);
    }

    /** Every base64 literal {@code script} decodes, in order. */
    private static List<String> embedded(String script) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("D '([A-Za-z0-9+/=]*)'").matcher(script);
        while (m.find()) out.add(new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8));
        return out;
    }

    private static Element toast(List<String> values) throws Exception {
        String xml =
                values.stream().filter(v -> v.startsWith("<toast")).findFirst().orElseThrow();
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
    }
}
