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
    void a_settled_episode_is_judged_by_its_last_run_and_stays_closed_across_a_resume() {
        EpisodeInference inference = new EpisodeInference("s", GAP);
        String first = inference.place(T, "/ws/app").episode();
        inference.fold(first, Fact.run(T, 5_000, "/ws/app", "app", false, List.of("QuoteTest#oneNightStay")));

        assertThat(inference.settle()).singleElement().satisfies(c -> {
            assertThat(c.outcomeChanged()).isTrue();
            assertThat(c.episode().outcome()).isEqualTo(Episode.RED);
            assertThat(c.episode().lastAt()).isEqualTo(T + 5_000);
        });
        assertThat(inference.settle()).as("settled once").isEmpty();

        EpisodeInference resumed = EpisodeInference.resume("s", GAP, inference.stored(), inference.state(), 0);
        assertThat(Objects.requireNonNull(resumed.current()).outcome()).isEqualTo(Episode.RED);
        assertThat(resumed.place(T + 6_000, "/ws/app").episode())
                .as("a settled episode takes no more facts, even inside the idle gap")
                .isEqualTo("s.2");
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
    void work_with_no_goal_that_starts_an_episode_takes_the_sessions_last_goal_and_the_goal_again_claims_it() {
        EpisodeInference inference = new EpisodeInference("s", GAP);
        inference.goal(T, "/ws/app", "Fix the weekend uplift");
        // Quiet past the idle gap, then a run with no goal: a new episode, titled by the last goal.
        long later = T + GAP + 1_000;
        EpisodeInference.Placement run = inference.place(later, "/ws/app");
        assertThat(run.changes())
                .extracting(c -> c.episode().number() + ":" + c.started())
                .containsExactly("1:false", "2:true");
        Episode fallback = Objects.requireNonNull(inference.current());
        assertThat(fallback.title()).isEqualTo("Fix the weekend uplift");
        assertThat(fallback.titleSource()).isEqualTo(Episode.LAST_GOAL);
        // Its facts never retitle it.
        inference.fold(run.episode(), Fact.run(later, 0, "/ws/app", "app", false, List.of("QuoteTest#oneNightStay")));
        assertThat(Objects.requireNonNull(inference.current()).title()).isEqualTo("Fix the weekend uplift");

        EpisodeInference.Placement again = inference.goal(later + 1_000, "/ws/app", "Fix the weekend uplift");
        assertThat(again.episode()).isEqualTo(run.episode());
        assertThat(again.changes()).singleElement().satisfies(c -> {
            assertThat(c.titleChanged()).isTrue();
            assertThat(c.episode().titleSource()).isEqualTo(Episode.GOAL);
        });
    }

    @Test
    void a_session_that_never_named_a_goal_keeps_inferring() {
        EpisodeInference inference = new EpisodeInference("s", GAP);
        inference.place(T, "/ws/app");
        inference.place(T + GAP + 1_000, "/ws/app");
        assertThat(inference.episodes())
                .extracting(Episode::titleSource)
                .containsExactly(Episode.INFERRED, Episode.INFERRED);
    }

    @Test
    void a_red_streak_ending_green_does_not_close_a_goals_episode_and_a_resumed_session_keeps_its_last_goal() {
        EpisodeInference inference = new EpisodeInference("s", GAP);
        String fix = inference.goal(T, "/ws/app", "Fix the weekend uplift").episode();
        inference.fold(fix, Fact.run(T, 0, "/ws/app", "app", false, List.of("QuoteTest#oneNightStay")));
        inference.fold(
                inference.place(T + 1_000, "/ws/app").episode(),
                Fact.run(T + 1_000, 0, "/ws/app", "app", true, List.of()));
        assertThat(inference.place(T + 2_000, "/ws/app").episode()).isEqualTo(fix);
        assertThat(Objects.requireNonNull(inference.current()).outcome()).isEqualTo(Episode.WORKING);
        assertThat(inference
                        .goal(T + 3_000, "/ws/app", "Fix the weekend uplift")
                        .episode())
                .isEqualTo(fix);

        EpisodeInference resumed = EpisodeInference.resume("s", GAP, inference.stored(), inference.state(), 0);
        resumed.place(T + 3_000 + GAP + 1_000, "/ws/lib");
        assertThat(resumed.episodes())
                .extracting(e -> e.project() + ":" + e.title() + ":" + e.titleSource())
                .containsExactly("/ws/app:Fix the weekend uplift:goal", "/ws/lib:Fix the weekend uplift:lastGoal");
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
        EpisodeInference after = EpisodeInference.resume("s", GAP, roundTrip(before.stored()), state, 0);

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

        EpisodeInference after =
                EpisodeInference.resume("s", GAP, List.of(new EpisodeInference.Stored(closed, Map.of())), null, 0);

        assertThat(after.episodes()).containsExactly(closed);
        assertThat(after.lastAt()).isEqualTo(T);
    }

    @Test
    void a_session_keeps_its_newest_episodes_and_numbers_on_past_the_ones_it_dropped() {
        EpisodeInference inference = new EpisodeInference("s", GAP);
        int total = EpisodeInference.KEEP + 7;
        for (int i = 0; i < total; i++) inference.place(T + i, "/ws/p" + i);

        assertThat(inference.episodes()).hasSize(EpisodeInference.KEEP);
        assertThat(inference.episodes().getFirst().number()).isEqualTo(8);
        assertThat(Objects.requireNonNull(inference.current()).id()).isEqualTo("s." + total);
        assertThat(inference.fold("s.1", Fact.run(T, 0, "/ws/p0", "p0", true, List.of())))
                .as("a run finishing into a dropped episode changes nothing")
                .isEmpty();

        EpisodeInference resumed = EpisodeInference.resume("s", GAP, inference.stored(), inference.state(), 0);
        assertThat(resumed.episodes()).isEqualTo(inference.episodes());
        assertThat(resumed.place(T + total, "/ws/other").episode()).isEqualTo("s." + (total + 1));

        EpisodeInference lost = EpisodeInference.resume("s", GAP, List.of(), null, 2105);
        assertThat(lost.place(T, "/ws/app").episode())
                .as("with no episodes stored, numbering carries on from the session's current one")
                .isEqualTo("s.2106");
    }

    /** {@code stored} as a journal hands it back: every value through JSON. */
    private static List<EpisodeInference.Stored> roundTrip(List<EpisodeInference.Stored> stored) {
        return stored.stream()
                .map(e -> new EpisodeInference.Stored(
                        Episode.of(cast((Map<?, ?>) Objects.requireNonNull(
                                MiniJson.parse(MiniJson.write(e.episode().toMap()))))),
                        cast((Map<?, ?>) Objects.requireNonNull(MiniJson.parse(MiniJson.write(e.state()))))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
