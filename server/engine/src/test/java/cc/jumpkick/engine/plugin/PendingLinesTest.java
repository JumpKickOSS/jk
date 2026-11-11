// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PendingLinesTest {

    @Test
    void past_its_cap_the_oldest_lines_go_and_are_counted() throws Exception {
        WarmWorkers.PendingLines lines = new WarmWorkers.PendingLines(3);
        for (int i = 1; i <= 5; i++) lines.add("line " + i);

        assertThat(lines.size()).isEqualTo(3);
        assertThat(lines.takeDropped()).isEqualTo(2);
        assertThat(lines.takeDropped()).isZero();
        assertThat(lines.poll(0, TimeUnit.MILLISECONDS)).isEqualTo("line 3");
        assertThat(lines.poll(0, TimeUnit.MILLISECONDS)).isEqualTo("line 4");
        assertThat(lines.poll(0, TimeUnit.MILLISECONDS)).isEqualTo("line 5");
        assertThat(lines.poll(10, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void a_worker_keeps_ten_thousand_unread_lines() {
        assertThat(WarmWorkers.PendingLines.CAP).isEqualTo(10_000);
    }

    @Test
    void a_waiting_poll_gets_a_line_added_while_it_waits() throws Exception {
        WarmWorkers.PendingLines lines = new WarmWorkers.PendingLines(10);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lines.add("late");
        });
        assertThat(lines.poll(5, TimeUnit.SECONDS)).isEqualTo("late");
    }
}
