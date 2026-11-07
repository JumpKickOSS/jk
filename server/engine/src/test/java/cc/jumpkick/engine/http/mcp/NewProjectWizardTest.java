// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.docs.AgentChoice;
import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A wizard link keeps its call's agents past the call's wait, until its deadline or its create. */
class NewProjectWizardTest {

    private static final AgentChoice CLAUDE = new AgentChoice(AgentSetup.Mode.DETECT, Set.of(AgentSetup.Host.CLAUDE));

    private final FakeClock clock = new FakeClock();
    private final NewProjectWizard wizard =
            new NewProjectWizard(() -> "http://127.0.0.1:8910/", () -> null, Duration.ofMinutes(5), clock);

    @Test
    void a_waiting_call_takes_the_create_and_the_create_spends_the_link() {
        String id = wizard.open(CLAUDE);
        var created = wizard.created(id);

        assertThat(wizard.agents(id)).isEqualTo(CLAUDE);
        assertThat(wizard.complete(id, Map.of("path", "/p"))).isTrue();
        assertThat(created).isCompletedWithValue(Map.of("path", "/p"));
        assertThat(wizard.agents(id)).isNull();
        assertThat(wizard.complete(id, Map.of("path", "/p"))).isFalse();
    }

    @Test
    void after_the_call_stops_waiting_the_agents_stay_until_the_deadline() {
        String id = wizard.open(CLAUDE);
        wizard.close(id);

        clock.advance(NewProjectWizard.LIFETIME.minusSeconds(1));
        assertThat(wizard.agents(id)).isEqualTo(CLAUDE);

        clock.advance(Duration.ofSeconds(1));
        assertThat(wizard.agents(id)).isNull();
        assertThat(wizard.complete(id, Map.of("path", "/p"))).isFalse();
    }

    @Test
    void a_create_after_the_call_stopped_waiting_is_told_no_call_took_it() {
        String id = wizard.open(CLAUDE);
        wizard.close(id);

        assertThat(wizard.agents(id)).isEqualTo(CLAUDE);
        assertThat(wizard.complete(id, Map.of("path", "/p"))).isFalse();
        assertThat(wizard.agents(id)).as("the create spent the link").isNull();
    }

    @Test
    void past_the_cap_the_oldest_link_goes() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i <= NewProjectWizard.MAX_OPEN; i++) {
            ids.add(wizard.open(CLAUDE));
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(wizard.agents(ids.getFirst())).isNull();
        assertThat(wizard.agents(ids.get(1))).isEqualTo(CLAUDE);
        assertThat(wizard.agents(ids.getLast())).isEqualTo(CLAUDE);
    }
}
