// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code jk history show} names a diagnostic's place once, whether the record kept it in the text or beside it. */
class HistoryDiagLocusTest {

    @Test
    void a_place_kept_beside_the_text_leads_the_row() {
        String row = "{\"type\":\"history-diag\",\"severity\":\"error\",\"code\":\"run-error\","
                + "\"message\":\"[build] unknown key `bogus`\",\"file\":\"/nowhere/ws/app/jk.toml\",\"line\":7,\"col\":1}";

        assertThat(HistoryCommand.HistoryShowCommand.diagLine(row))
                .isEqualTo("  [error] /nowhere/ws/app/jk.toml:7:1: [build] unknown key `bogus`");
    }

    @Test
    void a_place_the_text_already_names_is_not_repeated() {
        String row = "{\"type\":\"history-diag\",\"severity\":\"error\",\"task\":\"compile-main\","
                + "\"message\":\"/nowhere/src/A.java:3: error: ';' expected\",\"file\":\"/nowhere/src/A.java\",\"line\":3}";

        assertThat(HistoryCommand.HistoryShowCommand.diagLine(row))
                .isEqualTo("  [error] compile-main: /nowhere/src/A.java:3: error: ';' expected");
    }

    @Test
    void a_test_row_names_its_test_not_its_file() {
        String row = "{\"type\":\"history-diag\",\"severity\":\"error\",\"test\":\"AppTest > works\","
                + "\"message\":\"expected 1\",\"file\":\"/nowhere/src/AppTest.java\",\"line\":9}";

        assertThat(HistoryCommand.HistoryShowCommand.diagLine(row)).isEqualTo("  [error] AppTest > works — expected 1");
    }
}
