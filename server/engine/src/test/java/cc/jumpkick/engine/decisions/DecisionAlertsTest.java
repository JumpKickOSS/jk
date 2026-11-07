// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.UiAsk;
import cc.jumpkick.form.Form;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code [ui] ask}: the notification carries the deep link, open opens it, quiet does nothing. */
class DecisionAlertsTest {

    private static final String LINK = "http://127.0.0.1:8910/#decision/abc?t=T";

    private final List<List<String>> launched = new ArrayList<>();

    private DecisionAlerts on(String os, Map<String, String> env) {
        return new DecisionAlerts(launched::add, os, env::get);
    }

    private static Decision decision(boolean trust) {
        Form form = DecisionForms.confirm("disk-clean", "Clean jk's cache?", "Yes");
        return new Decision(
                "abc",
                "disk-clean",
                "Clean jk's cache?",
                List.of(),
                "jk decide abc",
                form,
                trust,
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
    void notify_sends_a_desktop_notification_that_carries_the_deep_link() {
        assertThat(on("Linux", Map.of()).raise(UiAsk.NOTIFY, decision(false), LINK))
                .isEqualTo("sent a desktop notification");
        assertThat(launched).singleElement().satisfies(argv -> {
            assertThat(argv.subList(0, 4))
                    .containsExactly("notify-send", "--app-name=jk", "--icon=dialog-question", DecisionAlerts.TITLE);
            assertThat(argv.getLast()).isEqualTo("Clean jk's cache?\nAnswer it in the dashboard.\n" + LINK);
        });
    }

    @Test
    void on_macos_the_notification_text_is_quoted_for_applescript() {
        on("Mac OS X", Map.of()).raise(UiAsk.NOTIFY, decision(true), LINK);
        assertThat(launched).singleElement().satisfies(argv -> {
            assertThat(argv.subList(0, 2)).containsExactly("osascript", "-e");
            assertThat(argv.get(2))
                    .startsWith("display notification \"Clean jk's cache?\nAccept it in a terminal: jk decide abc\n")
                    .contains(LINK)
                    .endsWith("with title \"" + DecisionAlerts.TITLE + "\"");
        });
    }

    @Test
    void open_opens_the_browser_on_the_link_and_quiet_does_nothing() {
        on("Linux", Map.of()).raise(UiAsk.OPEN, decision(false), LINK);
        on("Linux", Map.of("BROWSER", "firefox --new-tab")).raise(UiAsk.OPEN, decision(false), LINK);
        assertThat(on("Linux", Map.of()).raise(UiAsk.QUIET, decision(false), LINK))
                .startsWith("quiet");
        assertThat(launched).containsExactly(List.of("xdg-open", LINK), List.of("firefox", "--new-tab", LINK));
    }
}
