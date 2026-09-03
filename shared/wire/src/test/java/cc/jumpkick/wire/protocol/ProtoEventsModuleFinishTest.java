// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import org.junit.jupiter.api.Test;

/**: image-terminal outcome rides module-finish as additive nullable fields. */
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
                new ModuleOutcome.Image("reg.example/app:1.0", null, "app", "1.0", null));
        assertThat(Jsonl.bool(pushed, "hasImage", false)).isTrue();
        assertThat(Jsonl.str(pushed, "imageRef")).isEqualTo("reg.example/app:1.0");
        assertThat(Jsonl.str(pushed, "imageName")).isEqualTo("app");
        assertThat(Jsonl.str(pushed, "imageVersion")).isEqualTo("1.0");
        assertThat(Jsonl.str(pushed, "imageTarball")).isNull();
        assertThat(Jsonl.str(pushed, "imageDaemonExe")).isNull();
    }
}
