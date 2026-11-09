// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.UiAsk;
import cc.jumpkick.desktop.DesktopIdentity;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One channel per decision: in the page when a dashboard is visible, a browser notification from a
 * hidden dashboard that may show one, the native notification otherwise, and a tab whose report
 * went stale counts for nothing.
 */
class DashboardsTest {

    private static final String LINK = "http://127.0.0.1:8910/#decision/abc?t=T";

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final Dashboards dashboards = new Dashboards(clock::get);

    /** What each stream was handed to notify. */
    private final List<String> notified = new ArrayList<>();

    /** What the native alerts started. */
    private final List<List<String>> launched = new ArrayList<>();

    @TempDir
    Path userHome;

    private void open(String stream) {
        dashboards.open(stream, d -> notified.add(stream + ":" + d.id()));
    }

    private void advance(Duration by) {
        clock.addAndGet(by.toNanos());
    }

    private Decisions decisions(UiAsk ask) {
        DesktopIdentity identity = new DesktopIdentity(
                "Windows",
                JkDirs.of(name -> null, userHome.toString(), "Windows"),
                userHome,
                name -> null,
                argv -> true);
        DecisionAlerts alerts = new DecisionAlerts(launched::add, "Windows", name -> null, identity);
        return new Decisions(new DecisionQueue(), id -> LINK, dashboards)
                .alerts(alerts)
                .ask(() -> ask);
    }

    private static Decision decision() {
        return new Decision(
                "abc",
                "disk-clean",
                "Clean jk's cache?",
                List.of(),
                "jk decide abc",
                DecisionForms.confirm("disk-clean", "Clean jk's cache?", "Yes"),
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
    void no_dashboard_means_the_native_notification() {
        assertThat(dashboards.channel()).isEqualTo(new Dashboards.Channel.Native());
        open("a");
        assertThat(dashboards.channel())
                .as("a stream that has not reported yet")
                .isEqualTo(new Dashboards.Channel.Native());
    }

    @Test
    void a_visible_dashboard_keeps_it_in_the_page_whatever_else_is_open() {
        open("hidden");
        open("shown");
        dashboards.report("hidden", false, Dashboards.Permission.GRANTED);
        dashboards.report("shown", true, Dashboards.Permission.DEFAULT);
        assertThat(dashboards.channel()).isEqualTo(new Dashboards.Channel.InApp());
    }

    @Test
    void a_hidden_dashboard_with_permission_notifies_and_the_freshest_one_does() {
        open("old");
        open("new");
        open("asking");
        dashboards.report("old", false, Dashboards.Permission.GRANTED);
        advance(Duration.ofSeconds(5));
        dashboards.report("new", false, Dashboards.Permission.GRANTED);
        dashboards.report("asking", false, Dashboards.Permission.DEFAULT);
        assertThat(dashboards.channel()).isEqualTo(new Dashboards.Channel.Browser("new"));
    }

    @Test
    void hidden_dashboards_without_permission_leave_it_to_the_native_notification() {
        open("a");
        open("b");
        dashboards.report("a", false, Dashboards.Permission.DEFAULT);
        dashboards.report("b", false, Dashboards.Permission.DENIED);
        assertThat(dashboards.channel()).isEqualTo(new Dashboards.Channel.Native());
    }

    @Test
    void a_stale_report_falls_back_to_the_native_notification() {
        open("a");
        dashboards.report("a", true, Dashboards.Permission.GRANTED);
        advance(Dashboards.STALE_AFTER);
        assertThat(dashboards.channel()).as("exactly at the limit").isEqualTo(new Dashboards.Channel.InApp());
        advance(Duration.ofMillis(1));
        assertThat(dashboards.channel()).as("past it").isEqualTo(new Dashboards.Channel.Native());

        dashboards.report("a", false, Dashboards.Permission.GRANTED);
        assertThat(dashboards.channel())
                .as("a fresh report counts again")
                .isEqualTo(new Dashboards.Channel.Browser("a"));
        assertThat(Dashboards.STALE_AFTER).isGreaterThanOrEqualTo(Dashboards.PING_EVERY.multipliedBy(3));
    }

    @Test
    void a_closed_stream_leaves_at_once_and_takes_no_more_reports() {
        open("a");
        dashboards.report("a", true, Dashboards.Permission.GRANTED);
        dashboards.close("a");
        assertThat(dashboards.channel()).isEqualTo(new Dashboards.Channel.Native());
        assertThat(dashboards.report("a", true, Dashboards.Permission.GRANTED)).isFalse();
        assertThat(dashboards.notify("a", decision())).isFalse();
    }

    @Test
    void permission_reads_what_the_browser_says_and_anything_else_as_unsupported() {
        assertThat(Dashboards.Permission.parse("granted")).isEqualTo(Dashboards.Permission.GRANTED);
        assertThat(Dashboards.Permission.parse(" Denied ")).isEqualTo(Dashboards.Permission.DENIED);
        assertThat(Dashboards.Permission.parse("default")).isEqualTo(Dashboards.Permission.DEFAULT);
        assertThat(Dashboards.Permission.parse(null)).isEqualTo(Dashboards.Permission.UNSUPPORTED);
        assertThat(Dashboards.Permission.parse("prompt")).isEqualTo(Dashboards.Permission.UNSUPPORTED);
    }

    @Test
    void a_visible_dashboard_skips_every_notification() {
        open("a");
        dashboards.report("a", true, Dashboards.Permission.GRANTED);
        assertThat(decisions(UiAsk.NOTIFY).alert(decision(), LINK))
                .isEqualTo("a dashboard is showing: its bell has it");
        assertThat(notified).isEmpty();
        assertThat(launched).as("no native toast").isEmpty();
    }

    @Test
    void a_hidden_dashboard_with_permission_notifies_and_the_native_toast_is_skipped() {
        open("a");
        dashboards.report("a", false, Dashboards.Permission.GRANTED);
        assertThat(decisions(UiAsk.NOTIFY).alert(decision(), LINK)).isEqualTo("sent a browser notification");
        assertThat(notified).containsExactly("a:abc");
        assertThat(launched).as("no native toast").isEmpty();
    }

    @Test
    void with_no_dashboard_that_can_show_it_the_native_toast_fires() {
        assertThat(decisions(UiAsk.NOTIFY).alert(decision(), LINK)).isEqualTo("sent a desktop notification");
        assertThat(launched).singleElement().satisfies(argv -> assertThat(String.join(" ", argv))
                .contains("powershell"));
    }

    @Test
    void a_stale_heartbeat_falls_back_to_the_native_toast() {
        open("a");
        dashboards.report("a", false, Dashboards.Permission.GRANTED);
        advance(Dashboards.STALE_AFTER.plusSeconds(1));
        assertThat(decisions(UiAsk.NOTIFY).alert(decision(), LINK)).isEqualTo("sent a desktop notification");
        assertThat(notified).isEmpty();
        assertThat(launched).hasSize(1);
    }

    @Test
    void the_other_ask_modes_ignore_the_dashboards() {
        open("a");
        dashboards.report("a", false, Dashboards.Permission.GRANTED);
        assertThat(decisions(UiAsk.QUIET).alert(decision(), LINK)).isEqualTo("quiet: the link is above");
        assertThat(notified).isEmpty();
        assertThat(launched).isEmpty();
    }
}
