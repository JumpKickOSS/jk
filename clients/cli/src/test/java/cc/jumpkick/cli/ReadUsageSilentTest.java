// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MainSources;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A command at a terminal says nothing about reading agents' token counts: that notice belongs to
 * the dashboard's bell. The hints after a command run only under a controlling terminal, so the rule
 * is held on the client's sources, where no code may print the notice or its opt-out.
 */
class ReadUsageSilentTest {

    @Test
    void no_client_code_prints_the_read_usage_notice() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MainSources.locate())) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).isNotEmpty();
        List<String> printing = sources.stream()
                .filter(p -> {
                    try {
                        String text = Files.readString(p);
                        return text.contains("ReadUsage.NOTICE") || text.contains("ReadUsage.OFF");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .map(p -> p.getFileName().toString())
                .toList();
        assertThat(printing).isEmpty();
    }
}
