// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The parts of inference a replay of finished facts cannot show: a run that outlives its episode, and goals. */
class EpisodeInferenceTest {

    private static final long T = 1_700_000_000_000L;
    private static final long GAP = EpisodeInference.DEFAULT_IDLE_GAP_MILLIS;

    @Test
    void a_run_belongs_to_the_episode_it_was_admitted_into_even_when_it_finishes_in_the_next() {
        EpisodeInference inference = new EpisodeInference("s", GAP);
        String first = inference.place(T, "/ws/app").episode();
        // A question about another project starts episode 2 while the run is still going.
        Fact question = Fact.action(T + 1_000, 0, "/ws/lib", Fact.Kind.GRAPH, true, "", 0);
        inference.fold(inference.place(question.at(), question.project()).episode(), question);
        inference.fold(first, Fact.run(T, 5_000, "/ws/app", "app", false, List.of("QuoteTest#oneNightStay")));

        List<Episode> episodes = inference.episodes();
        assertThat(episodes)
                .extracting(Episode::title)
                .containsExactly("Fixing QuoteTest#oneNightStay", "Exploring the code");
        assertThat(episodes.getFirst().runs()).isEqualTo(1);
        assertThat(episodes.getFirst().outcome()).isEqualTo(Episode.RED);
    }

    @Test
    void a_goal_titles_a_new_episode_and_the_same_goal_again_keeps_it() {
        EpisodeInference inference = new EpisodeInference("s", GAP);
        Fact run = Fact.run(T, 0, "/ws/app", "app", true, List.of());
        inference.fold(inference.place(T, "/ws/app").episode(), run);

        EpisodeInference.Placement fix = inference.goal(T + 1_000, "/ws/app", "Fix the weekend uplift");
        assertThat(fix.changes())
                .extracting(c -> c.episode().number() + ":" + c.started())
                .containsExactly("1:false", "2:true");
        EpisodeInference.Placement again = inference.goal(T + 2_000, "/ws/app", "Fix the weekend uplift");
        assertThat(again.episode()).isEqualTo(fix.episode());
        assertThat(again.changes()).isEmpty();
        // Facts in a goal's episode never retitle it.
        Fact red = Fact.run(T + 3_000, 0, "/ws/app", "app", false, List.of("QuoteTest#oneNightStay"));
        assertThat(inference.fold(fix.episode(), red)).isEmpty();

        Episode current = Objects.requireNonNull(inference.current());
        assertThat(current.title()).isEqualTo("Fix the weekend uplift");
        assertThat(current.titleSource()).isEqualTo(Episode.GOAL);
        assertThat(inference.episodes().getFirst().outcome()).isEqualTo(Episode.GREEN);

        inference.goal(T + 4_000, "/ws/app", "Add Postgres");
        assertThat(inference.episodes())
                .extracting(Episode::title)
                .containsExactly("Working in app", "Fix the weekend uplift", "Add Postgres");
    }

    @Test
    void a_goal_episode_ended_with_nothing_in_it_is_abandoned() {
        EpisodeInference inference = new EpisodeInference("s", GAP);
        inference.goal(T, "/ws/app", "Add Postgres");
        inference.goal(T + 1_000, "/ws/app", "Fix the weekend uplift");
        assertThat(inference.episodes().getFirst().outcome()).isEqualTo(Episode.ABANDONED);
    }

    @Test
    void each_step_reports_what_it_changed() {
        EpisodeInference inference = new EpisodeInference("s", GAP);
        EpisodeInference.Placement p = inference.place(T, "/ws/app");
        assertThat(p.changes()).singleElement().satisfies(c -> assertThat(c.started())
                .isTrue());
        List<EpisodeInference.Change> red =
                inference.fold(p.episode(), Fact.run(T, 0, "/ws/app", "app", false, List.of("A#b")));
        assertThat(red).singleElement().satisfies(c -> {
            assertThat(c.titleChanged()).isTrue();
            assertThat(c.outcomeChanged()).isFalse();
        });
        List<EpisodeInference.Change> green = inference.fold(
                inference.place(T + 1, "/ws/app").episode(), Fact.run(T + 1, 0, "/ws/app", "app", true, List.of()));
        assertThat(green).singleElement().satisfies(c -> {
            assertThat(c.titleChanged()).isFalse();
            assertThat(c.outcomeChanged()).isTrue();
            assertThat(c.episode().outcome()).isEqualTo(Episode.GREEN);
        });
    }

    @Test
    void a_resumed_inference_carries_on_as_the_one_it_was_stored_from() {
        EpisodeInference before = new EpisodeInference("s", GAP);
        before.place(T, "/ws/app");
        before.fold("s.1", Fact.run(T, 100, "/ws/app", "app", true, List.of()));
        String red = before.place(T + 1_000, "/ws/app").episode();
        before.fold(
                red,
                Fact.run(T + 1_000, 100, "/ws/app", "app", false, List.of("QuoteTest#weekend", "QuoteTest#night")));

        Map<String, Object> state =
                MiniJson.parse(MiniJson.write(before.state())) instanceof Map<?, ?> m ? cast(m) : Map.of();
        EpisodeInference after = EpisodeInference.resume("s", GAP, before.episodes(), state);

        assertThat(after.episodes()).isEqualTo(before.episodes());
        assertThat(after.lastAt()).isEqualTo(before.lastAt());
        assertThat(after.place(T + 2_000, "/ws/app").episode()).isEqualTo(red);
        assertThat(after.fold(red, Fact.run(T + 2_000, 100, "/ws/app", "app", true, List.of())))
                .singleElement()
                .satisfies(c -> assertThat(c.episode())
                        .as("the red streak stored before the hand-off is the one this green run ends")
                        .extracting(Episode::outcome, Episode::title)
                        .containsExactly(Episode.GREEN, "Fixing QuoteTest#weekend (+1 more)"));
    }

    @Test
    void an_episode_stored_with_no_state_keeps_its_outcome() {
        Episode closed = new Episode(
                "s.1", "s", 1, "/ws/app", null, "Working in app", Episode.INFERRED, Episode.RED, T, T, 1, 0);

        EpisodeInference after = EpisodeInference.resume("s", GAP, List.of(closed), null);

        assertThat(after.episodes()).containsExactly(closed);
        assertThat(after.lastAt()).isEqualTo(T);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
