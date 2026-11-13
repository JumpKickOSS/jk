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

    private static final SetupChange ROW =
            new SetupChange(SetupChange.LONG_PATHS, "Long paths", "why", "effect", SetupChange.Kind.LONG_PATHS, null);

    @Test
    void applyAndNeverSettleTheOfferWithoutAsking() {
        SetupPlan open = new SetupPlan(List.of(ROW));

        assertThat(SetupOffer.of(SetupConsent.ASK, open, false)).isEqualTo(SetupOffer.ASK);
        assertThat(SetupOffer.of(SetupConsent.APPLY, open, false)).isEqualTo(SetupOffer.APPLY);
        assertThat(SetupOffer.of(SetupConsent.NEVER, open, false)).isEqualTo(SetupOffer.DECLINE);
    }

    @Test
    void offersOnlyRowsNeverAnsweredAndNotWhileADecisionIsOut() {
        SetupMemo answered = new SetupMemo(
                SetupMemo.Answer.DECLINED, 1, 0, List.of(), List.of(), List.of(), Map.of(), 0, 0, List.of(ROW.id()));
        SetupPlan plan = new SetupPlan(List.of(ROW));

        for (SetupConsent consent : SetupConsent.values()) {
            assertThat(SetupOffer.of(consent, plan.unanswered(answered), false)).isEqualTo(SetupOffer.NONE);
            assertThat(SetupOffer.of(consent, plan, true)).isEqualTo(SetupOffer.NONE);
            assertThat(SetupOffer.of(consent, new SetupPlan(List.of()), false)).isEqualTo(SetupOffer.NONE);
        }
    }
}
