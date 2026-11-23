// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.orchestrator.OrchestratorCode;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.KeyContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run-tests stamp names the engine only by the code that picks and launches a suite ({@link
 * KeyContract#SUITE}) and the code that judges it ({@link OrchestratorCode}): a reinstalled engine
 * whose change lies elsewhere replays every green suite, and one that changes either runs them
 * again.
 */
class TestStampEngineCodeTest {

    @AfterEach
    void forget() {
        BuildIdentity.overrideBuildIdForTests(null);
        KeyContract.SUITE.overrideDigestForTests(null);
        KeyContract.ARTIFACT.overrideDigestForTests(null);
        OrchestratorCode.overrideDigestForTests(null);
    }

    @Test
    void another_engine_with_the_same_suite_code_keeps_the_stamp(@TempDir Path tmp) throws Exception {
        BuildIdentity.overrideBuildIdForTests("engine000001");
        List<String> one = extras(tmp);
        BuildIdentity.overrideBuildIdForTests("engine000002");
        KeyContract.ARTIFACT.overrideDigestForTests("artifact0002");
        assertThat(extras(tmp)).as("the suites replay").isEqualTo(one);
    }

    @Test
    void changed_suite_code_moves_the_stamp(@TempDir Path tmp) throws Exception {
        KeyContract.SUITE.overrideDigestForTests("suites000001");
        List<String> one = extras(tmp);
        assertThat(one).contains("jk:" + BuildIdentity.cacheKeyVersion() + "+suites000001");
        KeyContract.SUITE.overrideDigestForTests("suites000002");
        List<String> two = extras(tmp);
        assertThat(two).contains("jk:" + BuildIdentity.cacheKeyVersion() + "+suites000002");
        assertThat(two).as("the suites run again").isNotEqualTo(one);
    }

    @Test
    void changed_judging_code_moves_the_stamp(@TempDir Path tmp) throws Exception {
        OrchestratorCode.overrideDigestForTests("judges000001");
        List<String> one = extras(tmp);
        assertThat(one).contains("orchestrator:judges000001");
        OrchestratorCode.overrideDigestForTests("judges000002");
        List<String> two = extras(tmp);
        assertThat(two).contains("orchestrator:judges000002");
        assertThat(two).as("the suites run again").isNotEqualTo(one);
    }

    @Test
    void the_same_judging_code_under_another_engine_keeps_the_stamp(@TempDir Path tmp) throws Exception {
        OrchestratorCode.overrideDigestForTests("judges000001");
        BuildIdentity.overrideBuildIdForTests("engine000001");
        List<String> one = extras(tmp);
        BuildIdentity.overrideBuildIdForTests("engine000002");
        assertThat(extras(tmp)).as("the suites replay").isEqualTo(one);
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
