// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@code src/test/js/app.test.mjs} with {@code node --test}. app.js has static ESM imports
 * of its sibling modules, so the whole set is copied into a {@code type:module} temp dir and
 * imported by its real name (unlike the single-file code/api harnesses). Skipped when Node
 * isn't installed.
 */
class WebClientAppTest {

    @Test
    void app_route_logic_passes_the_node_test_suite(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not installed — skipping the JS app suite");

        Path moduleRoot = WebClientFoldTest.moduleRoot();
        Path web = moduleRoot.resolve("src/main/resources/web");
        for (String f : List.of("app.js", "api.js", "fold.js", "tip.js", "code.js")) {
            assertThat(web.resolve(f)).exists();
            Files.copy(web.resolve(f), tempDir.resolve(f));
        }
        Files.writeString(tempDir.resolve("package.json"), "{\"type\":\"module\"}\n");
        Path testMjs = moduleRoot.resolve("src/test/js/app.test.mjs");
        assertThat(testMjs).exists();

        ProcessBuilder builder = new ProcessBuilder(
                        "node", "--test", testMjs.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .directory(tempDir.toFile());
        builder.environment().put("JK_APP_DIR", tempDir.toAbsolutePath().toString());
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
