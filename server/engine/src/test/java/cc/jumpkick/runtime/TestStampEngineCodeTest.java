// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.test.SuiteCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run-tests stamp keys the engine by the code that picks, launches and judges a suite, not by the
 * whole engine: a reinstalled engine whose change lies elsewhere replays every green suite, and one
 * that changes that code runs them again.
 */
class TestStampEngineCodeTest {

    @AfterEach
    void forget() {
        BuildIdentity.overrideBuildIdForTests(null);
        SuiteCode.overrideIdForTests(null);
    }

    @Test
    void another_engine_with_the_same_suite_code_keeps_the_stamp(@TempDir Path tmp) throws Exception {
        BuildIdentity.overrideBuildIdForTests("engine000001");
        List<String> one = extras(tmp);
        BuildIdentity.overrideBuildIdForTests("engine000002");
        if (JkVersion.VERSION.endsWith("-SNAPSHOT")) {
            assertThat(BuildIdentity.cacheKeyVersion())
                    .as("the engines differ, and so do their artifact keys")
                    .endsWith("+engine000002");
        }
        assertThat(extras(tmp)).as("the suites replay").isEqualTo(one);
    }

    @Test
    void changed_suite_code_moves_the_stamp(@TempDir Path tmp) throws Exception {
        SuiteCode.overrideIdForTests("suites000001");
        List<String> one = extras(tmp);
        assertThat(one).contains("jk:" + BuildIdentity.keyVersion("suites000001"));
        SuiteCode.overrideIdForTests("suites000002");
        List<String> two = extras(tmp);
        assertThat(two).contains("jk:" + BuildIdentity.keyVersion("suites000002"));
        if (JkVersion.VERSION.endsWith("-SNAPSHOT")) {
            assertThat(two).as("the suites run again").isNotEqualTo(one);
        }
    }

    private static List<String> extras(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """);
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
        return TestStampInputs.of(TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .extras();
    }
}
