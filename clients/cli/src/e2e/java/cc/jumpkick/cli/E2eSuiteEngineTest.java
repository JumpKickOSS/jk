// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The e2e suite runs on the same harness as the unit suite: the fixtures' engine extension
 * materializes the engine under test into the sandbox home, so a verb the engine hosts answers
 * from it ({@code jk test -p integration -s e2e -m clients/cli}).
 */
@Tag("integration")
class E2eSuiteEngineTest {

    @Test
    void an_engine_hosted_verb_answers_from_the_engine_under_test(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "e2e"
                version = "0.1.0"
                java = 25
                """);

        assertThat(EngineTestSupport.materialized())
                .as("the engine extension ran for this suite")
                .isTrue();
        assertThat(Jk.execute("info", "-C", dir.toString())).isZero();
    }
}
