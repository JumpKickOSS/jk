// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessLedgerTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    @TempDir
    Path tmp;

    @Test
    void uses_on_different_days_count_as_days_and_uses_within_a_window_count_once() {
        AccessLedger ledger = AccessLedger.at(tmp.resolve("a"));
        ledger.touch("k", 10 * DAY);
        ledger.touch("k", 10 * DAY + 1000); // inside the hot window: not a second use
        ledger.touch("k", 12 * DAY);
        ledger.touch("k", 12 * DAY + AccessLedger.EVERY_MILLIS + 1); // same day, a later use

        assertThat(ledger.load().get("k"))
                .isEqualTo(new AccessLedger.Use(10 * DAY, 12 * DAY + AccessLedger.EVERY_MILLIS + 1, 3, 2));
    }

    @Test
    void a_fold_keeps_the_live_keys_and_empties_the_journal() throws Exception {
        Path dir = tmp.resolve("b");
        AccessLedger ledger = AccessLedger.at(dir);
        ledger.touch("kept", 5 * DAY);
        ledger.touch("gone", 5 * DAY);
        ledger.flush();

        ledger.compact("kept"::equals);

        assertThat(dir.resolve("journal")).doesNotExist();
        assertThat(ledger.load()).containsOnlyKeys("kept");
        ledger.touch("kept", 7 * DAY);
        assertThat(ledger.load().get("kept")).extracting(AccessLedger.Use::days).isEqualTo(2);
    }

    @Test
    void a_torn_line_is_skipped_and_the_rest_read() throws Exception {
        Path dir = tmp.resolve("c");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("journal"), "1000\tgood\nnot-a-number\tbad\nhalf");

        assertThat(AccessLedger.at(dir).load()).containsOnlyKeys("good");
    }
}
