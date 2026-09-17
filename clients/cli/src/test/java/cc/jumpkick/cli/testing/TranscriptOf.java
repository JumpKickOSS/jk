// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The {@code details.jsonl} path a project's {@code target/jk-results.md} names as its step-by-step transcript. */
public final class TranscriptOf {

    private TranscriptOf() {}

    public static Path results(Path projectDir) throws IOException {
        String results = Files.readString(projectDir.resolve("target/jk-results.md"));
        Matcher details =
                Pattern.compile("transcript: `([^`]+details\\.jsonl)`").matcher(results);
        assertThat(details.find())
                .as("the results file names its details.jsonl:\n" + results)
                .isTrue();
        return Path.of(details.group(1));
    }
}
