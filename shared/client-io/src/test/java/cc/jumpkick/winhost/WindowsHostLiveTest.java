// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The real probes on a real Windows host: every row answers, none is unchecked, all within the budget. */
@Tag("live")
@EnabledOnOs(OS.WINDOWS)
class WindowsHostLiveTest {

    @Test
    void everyRowAnswersWithinTheBudgetWithoutAdmin() {
        Path home = Path.of(System.getProperty("user.home"), ".jk");
        Path project = Path.of("").toAbsolutePath();
        long start = System.nanoTime();
        List<HostRow> rows = new WindowsHostCheck(HostFacts.live(), SetupMemo.NONE, home, project).rows();
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(took).isLessThan(Duration.ofSeconds(1));
        assertThat(rows).hasSize(7).noneMatch(r -> r.detail().startsWith("not checked"));
        assertThat(HostFacts.live()
                        .registry()
                        .string(Registry.Hive.LOCAL_MACHINE, WindowsHostCheck.ENV_KEY, "PROCESSOR_ARCHITECTURE"))
                .isNotBlank();
        assertThat(HostFacts.live().volume(home)).isNotNull();
    }
}
