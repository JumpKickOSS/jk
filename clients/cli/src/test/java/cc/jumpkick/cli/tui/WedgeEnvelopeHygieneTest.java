// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the blank-line envelope rule: never print a rendered {@link CommandWedge} fail/ok/working
 * chip via raw {@code CliOutput} — use {@code printFail}/{@code printOk}/{@code printWorking} (or
 * {@code envelopeStart} + body) so the leading blank cannot be skipped.
 */
class WedgeEnvelopeHygieneTest {

    private static final Pattern ANTI = Pattern.compile(
            "CliOutput\\.(err|out)\\(\\s*(?:cc\\.jumpkick\\.cli\\.tui\\.)?CommandWedge\\.(fail|ok|working|chip)\\s*\\(");

    @Test
    void no_raw_command_wedge_prints_without_print_helpers() throws IOException {
        Path main = locateMainSources();
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

    private static Path locateMainSources() {
        // test cwd is typically the module root (clients/cli) or the monorepo root
        Path fromModule = Path.of("src/main/java");
        if (Files.isDirectory(fromModule)) return fromModule.toAbsolutePath().normalize();
        Path fromRepo = Path.of("clients/cli/src/main/java");
        if (Files.isDirectory(fromRepo)) return fromRepo.toAbsolutePath().normalize();
        throw new IllegalStateException(
                "cannot locate clients/cli main sources from " + Path.of(".").toAbsolutePath());
    }
}
