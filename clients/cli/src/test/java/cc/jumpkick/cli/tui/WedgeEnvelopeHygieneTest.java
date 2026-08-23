// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.testing.MainSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Style guard: fail/ok/working chips should use {@code printFail}/{@code printOk}/{@code
 * printWorking} so they land on the right stream with the right glyph. The leading blank itself
 * is owned by {@link cc.jumpkick.cli.CliOutput} — raw {@code CliOutput.out(wedgeLine)} is fine.
 */
class WedgeEnvelopeHygieneTest {

    private static final Pattern ANTI = Pattern.compile(
            "CliOutput\\.(err|out)\\(\\s*(?:cc\\.jumpkick\\.cli\\.tui\\.)?CommandWedge\\.(fail|ok|working|chip)\\s*\\(");

    @Test
    void no_raw_command_wedge_prints_without_print_helpers() throws IOException {
        Optional<Path> mainOpt = MainSources.locate();
        assumeTrue(mainOpt.isPresent(), "cli main sources not adjacent to test classpath — skip hygiene scan");
        Path main = mainOpt.get();
        assertThat(main).exists();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(main)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = Files.readString(p);
                    var m = ANTI.matcher(text);
                    while (m.find()) {
                        int line = 1
                                + (int) text.substring(0, m.start())
                                        .chars()
                                        .filter(c -> c == '\n')
                                        .count();
                        offenders.add(main.relativize(p) + ":" + line);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(offenders)
                .as("use CommandWedge.printFail/printOk/printWorking instead of CliOutput+(fail|ok|working|chip)")
                .isEmpty();
    }
}
