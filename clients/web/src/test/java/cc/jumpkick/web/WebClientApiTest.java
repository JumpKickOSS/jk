// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@code src/test/js/api.test.mjs} with {@code node --test}. Skipped when Node isn't
 * installed (same policy as {@link WebClientFoldTest}).
 */
class WebClientApiTest {

    @Test
    void api_epoch_guard_passes_the_node_test_suite(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not installed — skipping the JS api suite");

        Path moduleRoot = WebClientFoldTest.moduleRoot();
        Path apiJs = moduleRoot.resolve("src/main/resources/web/api.js");
        Path testMjs = moduleRoot.resolve("src/test/js/api.test.mjs");
        assertThat(apiJs).as("api.js under %s", moduleRoot).exists();
        assertThat(testMjs).as("api.test.mjs under %s", moduleRoot).exists();
        Path apiMjs = tempDir.resolve("api.mjs");
        Files.copy(apiJs, apiMjs);

        ProcessBuilder builder = new ProcessBuilder(
                        "node", "--test", testMjs.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .directory(tempDir.toFile());
        builder.environment().put("JK_API_MJS", apiMjs.toAbsolutePath().toString());
        Process node = builder.start();
        String output = new String(node.getInputStream().readAllBytes());
        assertThat(node.waitFor(30, TimeUnit.SECONDS))
                .as("node --test finished")
                .isTrue();
        assertThat(node.exitValue()).as(output).isZero();
        assertThat(output).contains("fail 0");
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
