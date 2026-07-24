// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the dashboard's headless event-folding suite ({@code src/test/js/fold.test.mjs}) with
 * {@code node --test} — skipped when Node isn't installed (Node is a test-time convenience, never
 * a build dependency; see {@code docs/webclient.md}). {@code fold.js} is copied to a {@code .mjs}
 * so Node treats it as the ES module it is.
 *
 * <p>Paths are resolved against the module root (not {@code user.dir}): workspace builds run tests
 * with the engine CWD at the workspace root, so bare {@code src/...} relatives would miss
 * {@code clients/web/...}.
 */
class WebClientFoldTest {

    @Test
    void fold_logic_passes_the_node_test_suite(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not installed — skipping the JS fold suite");

        Path moduleRoot = moduleRoot();
        Path foldJs = moduleRoot.resolve("src/main/resources/web/fold.js");
        Path testMjs = moduleRoot.resolve("src/test/js/fold.test.mjs");
        assertThat(foldJs).as("fold.js under %s", moduleRoot).exists();
        assertThat(testMjs).as("fold.test.mjs under %s", moduleRoot).exists();
        Path foldMjs = tempDir.resolve("fold.mjs");
        Files.copy(foldJs, foldMjs);

        ProcessBuilder builder = new ProcessBuilder(
                        "node", "--test", testMjs.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .directory(tempDir.toFile());
        builder.environment().put("JK_FOLD_MJS", foldMjs.toAbsolutePath().toString());
        Process node = builder.start();
        String output = new String(node.getInputStream().readAllBytes());
        assertThat(node.waitFor(30, TimeUnit.SECONDS))
                .as("node --test finished")
                .isTrue();
        assertThat(node.exitValue()).as(output).isZero();
        assertThat(output).contains("fail 0"); // both node reporters ("# fail 0" TAP, "ℹ fail 0" spec)
    }

    /**
     * Module root containing {@code src/main/resources/web/fold.js}. Prefers cwd when already in
     * the module; otherwise {@code clients/web} under a workspace root; else walks parents / class
     * resource location.
     */
    static Path moduleRoot() throws IOException {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (isWebModuleRoot(cwd)) return cwd;
        Path nested = cwd.resolve("clients/web");
        if (isWebModuleRoot(nested)) return nested.normalize();

        // Classpath resource (target/classes/web/fold.js) → walk up for jk.toml + sources.
        URL res = WebClientFoldTest.class.getResource("/web/fold.js");
        if (res != null && "file".equals(res.getProtocol())) {
            try {
                Path resourceFile = Path.of(res.toURI()).toAbsolutePath().normalize();
                for (Path d = resourceFile.getParent(); d != null; d = d.getParent()) {
                    if (isWebModuleRoot(d)) return d;
                }
            } catch (URISyntaxException ignored) {
                // fall through
            }
        }

        for (Path d = cwd; d != null; d = d.getParent()) {
            Path web = d.resolve("clients/web");
            if (isWebModuleRoot(web)) return web.normalize();
            if (isWebModuleRoot(d)) return d;
        }
        throw new IOException("cannot locate jk-web module root from cwd=" + cwd);
    }

    private static boolean isWebModuleRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("jk.toml"))
                && Files.isRegularFile(dir.resolve("src/main/resources/web/fold.js"))
                && Files.isRegularFile(dir.resolve("src/test/js/fold.test.mjs"));
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
