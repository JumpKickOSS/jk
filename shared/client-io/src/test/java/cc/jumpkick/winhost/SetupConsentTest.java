// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupConsentTest {

    @TempDir
    Path tmp;

    private SetupConsent read(String toml) throws Exception {
        Path file = Files.writeString(tmp.resolve("config-" + toml.hashCode() + ".toml"), toml);
        return SetupConsent.read(file);
    }

    @Test
    void readsEachValueAndDefaultsToAsk() throws Exception {
        assertThat(SetupConsent.read(tmp.resolve("missing.toml"))).isEqualTo(SetupConsent.ASK);
        assertThat(read("[ui]\nask = \"notify\"\n")).isEqualTo(SetupConsent.ASK);
        assertThat(read("[windows]\nfix = \"apply\"\n")).isEqualTo(SetupConsent.APPLY);
        assertThat(read("[windows]\nfix = \"never\"\n")).isEqualTo(SetupConsent.NEVER);
        assertThat(read("[windows]\nfix = \"sometimes\"\n")).isEqualTo(SetupConsent.ASK);
    }

    @Test
    void applyAndNeverSettleTheOfferWithoutAsking() {
        SetupMemo unanswered = SetupMemo.NONE;

        assertThat(SetupOffer.of(SetupConsent.ASK, unanswered, false, false)).isEqualTo(SetupOffer.ASK);
        assertThat(SetupOffer.of(SetupConsent.APPLY, unanswered, false, false)).isEqualTo(SetupOffer.APPLY);
        assertThat(SetupOffer.of(SetupConsent.NEVER, unanswered, false, false)).isEqualTo(SetupOffer.DECLINE);
    }

    @Test
    void offersOnlyOnAMachineThatNeverAnswered() {
        SetupMemo declined =
                new SetupMemo(SetupMemo.Answer.DECLINED, 1, 0, List.of(), List.of(), List.of(), Map.of(), 0, 0);

        for (SetupConsent consent : SetupConsent.values()) {
            assertThat(SetupOffer.of(consent, declined, false, false)).isEqualTo(SetupOffer.NONE);
            assertThat(SetupOffer.of(consent, SetupMemo.NONE, true, false)).isEqualTo(SetupOffer.NONE);
            assertThat(SetupOffer.of(consent, SetupMemo.NONE, false, true)).isEqualTo(SetupOffer.NONE);
        }
    }
}
