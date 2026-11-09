// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The elevated script for real: one UAC prompt (none from an already elevated shell) excludes a
 * scratch folder, refuses an injected drive root, verifies the exclusion with Defender, and a second
 * run removes it again. It changes this machine's Defender settings while it runs.
 */
@Tag("live")
@EnabledOnOs(OS.WINDOWS)
class ElevatedLiveTest {

    @TempDir
    Path tmp;

    @Test
    void excludesVerifiesAndRefusesAnInjectedRootBehindOnePrompt() throws Exception {
        Path scratch = Files.createDirectories(tmp.resolve("ex"));
        Path result = tmp.resolve("result.txt");
        Forbidden rules = Forbidden.of(HostFacts.live(), System.getProperty("user.home") + "\\.jk");
        String script =
                ElevatedScript.renderUnchecked(List.of(scratch.toString(), "C:\\"), false, result.toString(), rules);
        try {
            Elevator.Outcome outcome = Elevator.live().run(script, Duration.ofMinutes(2));
            assertThat(outcome).isInstanceOf(Elevator.Outcome.Ran.class);
            assertThat(Files.readAllLines(result)).contains("verified\t" + scratch, "refused\tC:\\\ta drive root");
        } finally {
            String undo = "Remove-MpPreference -ExclusionPath " + ElevatedScript.quote(scratch.toString());
            Elevator.live().run(undo, Duration.ofMinutes(2));
        }
    }
}
