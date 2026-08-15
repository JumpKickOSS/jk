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
 * with the engine CWD at {@code ~/.local/state/jk/engine}, so bare {@code src/...} relatives miss
 * {@code clients/web/...}. Classpath output may live under {@code <workspace>/target/clients/web/}
 * (Mill-style) rather than {@code clients/web/target/}.
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
     * Regression: the fold layer normalizes the wire's {@code task} vocabulary back to
     * {@code step}/{@code steps} — app.js templates reading the FOLDED model must use the folded
     * names or they silently render empty (Vue resolves unknown fields to undefined).
     */
    @Test
    void app_templates_read_the_folded_vocabulary() throws Exception {
        String app = Files.readString(moduleRoot().resolve("src/main/resources/web/app.js"));
        assertThat(app)
                .doesNotContain("openPhase.tasks")
                .doesNotContain("mod.tasks")
                .doesNotContain("d.task)");
    }

    /**
     * Module root containing {@code src/main/resources/web/fold.js}. Prefers cwd when already in
     * the module; otherwise {@code clients/web} under a workspace root; else maps classpath output
     * under {@code target/<module-rel>/} back to the source module, then walks ancestors.
     */
    static Path moduleRoot() throws IOException {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (isWebModuleRoot(cwd)) return cwd;
        Path nested = cwd.resolve("clients/web");
        if (isWebModuleRoot(nested)) return nested.normalize();

        // Classpath resource: standalone layout (…/clients/web/target/classes/…/fold.js) or
        // workspace layout (…/target/clients/web/classes/…/fold.js).
        URL res = WebClientFoldTest.class.getResource("/web/fold.js");
        if (res != null && "file".equals(res.getProtocol())) {
            try {
                Path resourceFile = Path.of(res.toURI()).toAbsolutePath().normalize();
                Path fromOutput = moduleRootFromOutputPath(resourceFile);
                if (fromOutput != null) return fromOutput;
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

    /**
     * Map a path under a build output tree back to the source module root.
     *
     * <ul>
     *   <li>{@code <ws>/target/<module-rel>/…} → {@code <ws>/<module-rel>} (workspace layout)
     *   <li>{@code <module>/target/…} → {@code <module>} (standalone layout)
     * </ul>
     */
    static Path moduleRootFromOutputPath(Path somewhereUnderOutput) {
        Path abs = somewhereUnderOutput.toAbsolutePath().normalize();
        for (Path d = abs; d != null; d = d.getParent()) {
            if (d.getFileName() == null || !"target".equals(d.getFileName().toString())) continue;
            Path parentOfTarget = d.getParent();
            if (parentOfTarget == null) continue;

            // Standalone: <module>/target/…
            if (isWebModuleRoot(parentOfTarget)) return parentOfTarget.normalize();

            // Workspace: <ws>/target/<module-rel>/… — try successive path prefixes under <ws>.
            try {
                Path rel = d.relativize(abs);
                int n = rel.getNameCount();
                for (int i = 1; i <= n; i++) {
                    Path candidate = parentOfTarget.resolve(rel.subpath(0, i));
                    if (isWebModuleRoot(candidate)) return candidate.normalize();
                }
            } catch (IllegalArgumentException ignored) {
                // different roots — keep walking
            }
        }
        return null;
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
