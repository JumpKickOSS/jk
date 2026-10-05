// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A step's last label is its detail in the record, and survives the journal's JSON. */
class BuildAccumulatorStepDetailTest {

    @Test
    void the_last_label_a_step_sets_is_its_detail() {
        BuildAccumulator a = new BuildAccumulator("build", "/proj", "g:web", "cli");
        a.noteTaskStart("", "node-package", "package", 1_000L);
        a.noteLabel("", "node-package", "package web-0.1.0.jar · dist/ under static/");
        a.noteLabel("", "node-package", "package web-0.1.0.jar · dist/ under static/ · start: node server.js");
        a.addTask("", "node-package", "package", "SUCCESS", 40, 0);
        a.addTask("", "parse-build", "resolve", "SUCCESS", 3, 0);

        BuildRecord r = a.toRecord(2_000L, true, 1_000L, "9.9", null);
        BuildRecord.Task pkg = r.steps().stream()
                .filter(t -> t.name().equals("node-package"))
                .findFirst()
                .orElseThrow();
        assertThat(pkg.detail()).isEqualTo("package web-0.1.0.jar · dist/ under static/ · start: node server.js");
        assertThat(r.steps().stream()
                        .filter(t -> t.name().equals("parse-build"))
                        .findFirst()
                        .orElseThrow()
                        .detail())
                .isEmpty();

        BuildRecord back = Json.read(Json.write(r));
        assertThat(back.steps()).contains(pkg);
    }
}
