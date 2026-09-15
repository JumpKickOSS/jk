// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkCatalog;
import cc.jumpkick.jdk.JdkKeywords;
import cc.jumpkick.jdk.JdkSelector;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Param;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every example spec the {@code jk jdk} help screens suggest is one that resolves: a keyword, or a
 * spec {@link JdkSelector} selects an entry for on a feed shaped like the real one. A suggestion
 * the selector cannot honour is a help text that lies.
 */
class JdkHelpExamplesTest {

    private static final Pattern EXAMPLES = Pattern.compile("\\(ex: ([^)]*)\\)");

    @Test
    void every_example_spec_in_the_jdk_help_resolves() {
        JdkCatalog feed = feedLike();
        List<String> unresolved = new ArrayList<>();
        int examples = 0;
        for (CliCommand sub : new JdkCommand().subcommands()) {
            for (Param param : sub.parameters()) {
                Matcher m = EXAMPLES.matcher(param.description());
                while (m.find()) {
                    for (String spec : m.group(1).split(",\\s*")) {
                        examples++;
                        if (JdkKeywords.isKeyword(spec)) continue;
                        if (JdkSelector.selectPreferred(feed, spec, "linux", "x86_64")
                                .isEmpty()) {
                            unresolved.add("jk jdk " + sub.name() + ": " + spec);
                        }
                    }
                }
            }
        }
        assertThat(examples).as("the help screens carry example specs").isPositive();
        assertThat(unresolved).isEmpty();
    }

    /** The vendors the examples name, spelled as the JetBrains feed spells them. */
    private static JdkCatalog feedLike() {
        return new JdkCatalog(List.of(
                entry("Eclipse", "Temurin", "temurin-25", 25, "25.0.3", true, List.of("temurin-25.0.3", "temurin-25")),
                entry("Eclipse", "Temurin", "temurin-26", 26, "26", false, List.of("temurin-26")),
                entry("Oracle", "OpenJDK", "openjdk-26", 26, "26", true, List.of("openjdk-26")),
                entry("Oracle", "GraalVM", "graalvm-jdk-25", 25, "25", false, List.of("graalvm-jdk-25")),
                entry(
                        "GraalVM Community",
                        "GraalVM CE",
                        "graalvm-ce-25",
                        25,
                        "25.0.2",
                        false,
                        List.of("graalvm-ce-25.0.2", "graalvm-ce-25"))));
    }

    private static JdkCatalog.Entry entry(
            String vendor,
            String product,
            String suggestedSdkName,
            int major,
            String version,
            boolean defaultForMajor,
            List<String> aliases) {
        return new JdkCatalog.Entry(
                vendor,
                product,
                suggestedSdkName,
                major,
                version,
                defaultForMajor,
                false,
                aliases,
                "linux",
                "x86_64",
                "targz",
                URI.create("https://example.invalid/" + suggestedSdkName + ".tar.gz"),
                "00".repeat(32),
                1234L,
                suggestedSdkName + "." + version,
                "");
    }
}
