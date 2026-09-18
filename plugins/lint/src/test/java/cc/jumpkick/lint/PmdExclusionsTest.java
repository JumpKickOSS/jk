// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The exclusion file in Maven's shape: one binary class name per line, its rules comma-separated. */
class PmdExclusionsTest {

    @Test
    void a_line_names_a_class_and_the_rules_it_may_break(@TempDir Path tmp) throws Exception {
        PmdExclusions excluded = PmdExclusions.read(Files.writeString(tmp.resolve("pmd-exclude.properties"), """
                # TheAlgorithms' shape
                com.thealgorithms.ciphers.AES=UselessMainMethod
                com.thealgorithms.conversions.AnyBaseToAnyBase = UselessMainMethod, UselessParentheses

                """));

        assertThat(excluded.excludes("com.thealgorithms.ciphers", "AES", "UselessMainMethod"))
                .isTrue();
        assertThat(excluded.excludes("com.thealgorithms.ciphers", "AES", "UselessParentheses"))
                .isFalse();
        assertThat(excluded.excludes("com.thealgorithms.conversions", "AnyBaseToAnyBase", "UselessParentheses"))
                .isTrue();
        assertThat(excluded.excludes("com.thealgorithms.conversions", "Other", "UselessParentheses"))
                .isFalse();
        assertThat(excluded.size()).isEqualTo(2);
        assertThat(PmdExclusions.NONE.excludes("demo", "Sample", "UselessParentheses"))
                .isFalse();
    }
}
