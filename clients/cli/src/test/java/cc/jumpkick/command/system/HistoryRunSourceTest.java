// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code jk history show} names the CI run a build came from, and nothing for a local build. */
class HistoryRunSourceTest {

    @Test
    void a_ci_run_reads_as_provider_event_and_page() {
        String record = "{\"type\":\"history-record\",\"branch\":\"main\",\"ciProvider\":\"github-actions\","
                + "\"ciEvent\":\"push\",\"ciRunUrl\":\"https://github.com/acme/shop/actions/runs/8812\"}";

        assertThat(HistoryCommand.ciRun(record))
                .isEqualTo("github-actions (push) https://github.com/acme/shop/actions/runs/8812");
    }

    @Test
    void a_local_build_names_no_ci_run() {
        assertThat(HistoryCommand.ciRun("{\"type\":\"history-record\",\"branch\":\"main\",\"ciProvider\":null}"))
                .isNull();
    }
}
