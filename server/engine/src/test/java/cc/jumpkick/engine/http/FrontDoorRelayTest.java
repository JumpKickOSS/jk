// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.UiAsk;
import cc.jumpkick.desktop.DesktopIdentity;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.decisions.Dashboards;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionAlerts;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.Decisions;
import cc.jumpkick.engine.notices.Notices;
import cc.jumpkick.host.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The front door raises a paired engine's notifications for it: a relayed decision becomes this
 * engine's desktop notification, named for the engine and linking under its prefix with the front
 * door's token, and goes when the decision settles there; a change-log notice goes on this bell named
 * for the engine; a guest that is relayed raises nothing itself and says so.
 */
class FrontDoorRelayTest {

    private static final String GUEST = "3fcc9b6aa600";
    private static final FrontDoorRelay.Target TARGET =
            new FrontDoorRelay.Target(GUEST, 8995, "guest-token", "WSL fd3-ubuntu on Workstation");

    @TempDir
    Path home;

    /** Records the notifications it is asked to show: an old notify-send, so text and link are in argv. */
    private final List<List<String>> launched = new ArrayList<>();

    private final DecisionAlerts.Launcher launcher = new DecisionAlerts.Launcher() {
        @Override
        public boolean launch(List<String> argv) {
            launched.add(argv);
            return true;
        }

        @Override
        public @Nullable String output(List<String> argv) {
            return argv.contains("--version") ? "notify-send 0.7.9\n" : null;
        }
    };

    private DecisionAlerts alerts() {
        DesktopIdentity identity = new DesktopIdentity(
                "Linux", JkDirs.of(name -> null, home.toString(), "Linux"), home, name -> null, argv -> true);
        return new DecisionAlerts(launcher, "Linux", name -> null, identity);
    }

    private final Notices bell = new Notices();

    private final Decisions front =
            new Decisions(new DecisionQueue(), id -> null, new Dashboards()).ask(() -> UiAsk.NOTIFY);

    private FrontDoorRelay relay() {
        front.alerts(alerts());
        return new FrontDoorRelay(
                () -> null, () -> front, () -> bell, () -> "http://127.0.0.1:8910/", () -> "front-token", line -> {});
    }

    /** A decision the guest opened, as its events carry it. */
    private static Decision guestDecision(DecisionQueue guestQueue) {
        return guestQueue.open(new DecisionQueue.Ask(
                "disk-clean",
                "Clean JumpKick's cache?",
                List.of(),
                DecisionForms.confirm("disk-clean", "Clean JumpKick's cache?", "Yes"),
                null,
                null,
                null,
                null));
    }

    @Test
    void a_relayed_decision_is_raised_named_for_the_engine_and_withdrawn_when_it_settles() {
        FrontDoorRelay relay = relay();
        DecisionQueue guest = new DecisionQueue();
        Decision d = guestDecision(guest);
        relay.frame(TARGET, "alert", JsonOut.rawObject(d.toMap()).toString());
        relay.frame(TARGET, "alert", JsonOut.rawObject(d.toMap()).toString());

        assertThat(launched).as("raised once").hasSize(1);
        String argv = String.join(" ", launched.getFirst());
        assertThat(argv)
                .contains("WSL fd3-ubuntu on Workstation: Clean JumpKick's cache?")
                .contains("http://127.0.0.1:8910/engines/" + GUEST + "/#decision/" + d.id() + "?t=front-token")
                .doesNotContain("guest-token");

        Map<String, Object> settled = new LinkedHashMap<>(d.toMap());
        settled.put("state", "declined");
        relay.frame(TARGET, "decision", JsonOut.rawObject(settled).toString());
        // A second raise of the same decision would be a new notification: it is gone from the raised set.
        relay.frame(TARGET, "alert", JsonOut.rawObject(d.toMap()).toString());
        assertThat(launched).hasSize(2);
    }

    @Test
    void a_change_log_notice_goes_on_this_bell_and_a_decision_notice_does_not() {
        FrontDoorRelay relay = relay();
        relay.frame(TARGET, "notice", """
                {"change":"added","notice":{"id":"a","at":1,"kind":"change","text":"installed Temurin 25","href":"#activity"},"seenUpTo":0,"unseen":1}""");
        relay.frame(TARGET, "notice", """
                {"change":"added","notice":{"id":"b","at":2,"kind":"decision","text":"asks","href":"#decision/x","decision":"x"},"seenUpTo":0,"unseen":2}""");
        relay.frame(TARGET, "notice", """
                {"change":"seen","seenUpTo":2,"unseen":0}""");
        assertThat(bell.list()).singleElement().satisfies(n -> {
            assertThat(n.text()).isEqualTo("WSL fd3-ubuntu on Workstation: installed Temurin 25");
            assertThat(n.href()).isEqualTo("/engines/" + GUEST + "/#activity");
        });
    }

    @Test
    void garbage_frames_and_settled_alerts_raise_nothing() {
        FrontDoorRelay relay = relay();
        relay.frame(TARGET, "alert", "not json");
        relay.frame(TARGET, "alert", "{\"id\":\"x\"}");
        Map<String, Object> settled =
                new LinkedHashMap<>(guestDecision(new DecisionQueue()).toMap());
        settled.put("state", "accepted");
        relay.frame(TARGET, "alert", JsonOut.rawObject(settled).toString());
        assertThat(launched).isEmpty();
    }

    @Test
    void a_relayed_guest_raises_nothing_itself_and_hands_the_decision_on() {
        DecisionQueue queue = new DecisionQueue();
        List<Decision> handed = new ArrayList<>();
        Decisions guest = new Decisions(queue, id -> "http://127.0.0.1:8995/#decision/" + id, new Dashboards())
                .ask(() -> UiAsk.NOTIFY)
                .alerts(alerts())
                .relay(() -> true, handed::add);
        Decision d = guestDecision(queue);
        assertThat(guest.alert(d, "http://127.0.0.1:8995/#decision/" + d.id())).isEqualTo("the front door raises it");
        assertThat(handed).containsExactly(d);
        assertThat(launched).isEmpty();
        assertThat(queue.notified(d.id())).contains("front-door");

        Decisions alone = new Decisions(new DecisionQueue(), id -> null, new Dashboards())
                .ask(() -> UiAsk.NOTIFY)
                .alerts(alerts())
                .relay(() -> false, handed::add);
        alone.alert(d, "http://127.0.0.1:8995/#decision/" + d.id());
        assertThat(launched).as("not relayed: it notifies for itself").hasSize(1);
    }
}
