// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The image terminal, the shelf publish and a stop ride module-finish as additive fields. */
class ProtoEventsModuleFinishTest {

    @Test
    void image_outcome_fields_round_trip_and_are_omitted_when_absent() {
        String plain = ProtoEvents.moduleFinish("/w/app", "g:app", true, 0, 12, true, false);
        assertThat(plain).doesNotContain("hasImage");

        String pushed = ProtoEvents.moduleFinish(
                "/w/app",
                "g:app",
                true,
                0,
                12,
                true,
                false,
                new ModuleOutcome.Image("reg.example/app:1.0", null, "app", "1.0", null),
                null);
        assertThat(Jsonl.bool(pushed, "hasImage", false)).isTrue();
        assertThat(Jsonl.str(pushed, "imageRef")).isEqualTo("reg.example/app:1.0");
        assertThat(Jsonl.str(pushed, "imageName")).isEqualTo("app");
        assertThat(Jsonl.str(pushed, "imageVersion")).isEqualTo("1.0");
        assertThat(Jsonl.str(pushed, "imageTarball")).isNull();
        assertThat(Jsonl.str(pushed, "imageDaemonExe")).isNull();
    }

    @Test
    void shelf_publish_round_trips_and_is_omitted_when_the_module_shelved_nothing() {
        String plain = ProtoEvents.moduleFinish("/w/app", "g:app", true, 0, 12, true, false);
        assertThat(plain).doesNotContain("shelfCoord");
        assertThat(ModuleFinishEvent.decode(plain).shelved()).isNull();

        ModuleOutcome.Shelved shelved = new ModuleOutcome.Shelved("g:app:1.0", "a".repeat(64), "b".repeat(64));
        String line = ProtoEvents.moduleFinish("/w/app", "g:app", true, 0, 12, true, false, null, shelved);
        assertThat(Jsonl.str(line, "shelfCoord")).isEqualTo("g:app:1.0");
        assertThat(Jsonl.str(line, "shelfJarSha256")).isEqualTo("a".repeat(64));
        assertThat(Jsonl.str(line, "shelfPomSha256")).isEqualTo("b".repeat(64));
        assertThat(line).doesNotContain("hasImage");
        ModuleFinishEvent decoded = ModuleFinishEvent.decode(line);
        assertThat(decoded.shelved()).isEqualTo(shelved);
        assertThat(decoded.image()).isNull();
    }

    @Test
    void a_stopped_module_says_so_and_a_failed_one_carries_no_stopped_field() {
        String failed = ProtoEvents.moduleFinish("/w/a", "g:a", false, 1, 12, true, false);
        assertThat(failed).doesNotContain("stopped");
        assertThat(ModuleFinishEvent.decode(failed).stopped()).isFalse();

        String line = ProtoEvents.moduleFinish("/w/b", "g:b", false, 1, 12, true, false, true, null, null, null);
        assertThat(Jsonl.bool(line, "stopped", false)).isTrue();
        assertThat(Jsonl.bool(line, "success", true)).isFalse();
        ModuleOutcome outcome = ModuleFinishEvent.decode(line).outcome(Path.of("/w/b"));
        assertThat(outcome.stopped()).isTrue();
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.dir()).isEqualTo(Path.of("/w/b"));
    }
}
