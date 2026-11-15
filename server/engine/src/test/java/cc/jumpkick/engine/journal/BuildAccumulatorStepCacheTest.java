// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.StepCache;
import org.junit.jupiter.api.Test;

/** What the action cache did for a step rides its row in the record and survives the journal's JSON. */
class BuildAccumulatorStepCacheTest {

    @Test
    void a_steps_cache_outcome_is_on_its_row_and_round_trips() {
        BuildAccumulator a = new BuildAccumulator("build", "/proj", "g:web", "cli");
        StepCache miss = new StepCache(
                StepCache.Outcome.MISS,
                StepCache.Reason.INPUTS_CHANGED,
                "src/main/java/A.java",
                "k".repeat(64),
                0,
                812);
        a.noteTaskStart("", "compile-main", "compile", 1_000L);
        a.noteCache("", "compile-main", miss);
        a.addTask("", "compile-main", "compile", "SUCCESS", 40, 0);
        a.addTask("", "parse-build", "resolve", "SUCCESS", 3, 0);

        BuildRecord r = a.toRecord(2_000L, true, 1_000L, "9.9", null);
        BuildRecord.Task compile = r.steps().stream()
                .filter(t -> t.name().equals("compile-main"))
                .findFirst()
                .orElseThrow();
        assertThat(compile.cache()).isEqualTo(miss);
        assertThat(r.steps().stream()
                        .filter(t -> t.name().equals("parse-build"))
                        .findFirst()
                        .orElseThrow()
                        .cache())
                .isNull();

        String json = Json.write(r);
        assertThat(json)
                .containsPattern("\"reason\":\\s*\"inputs-changed\"")
                .containsPattern("\"outcome\":\\s*\"miss\"");
        assertThat(Json.read(json).steps()).contains(compile);
    }

    @Test
    void remote_cache_traffic_is_counted_apart_from_repository_traffic() {
        BuildAccumulator a = new BuildAccumulator("build", "/proj", "g:web", "cli");
        a.io().remoteDown(100);
        a.io().cacheDown(30);
        a.io().cacheUp(7);

        BuildRecord.Io io = Json.read(Json.write(a.toRecord(2_000L, false, 1_000L, "9.9", null)))
                .io();

        assertThat(io).isEqualTo(new BuildRecord.Io(0, 100, 0, 0, 7, 30));
    }
}
