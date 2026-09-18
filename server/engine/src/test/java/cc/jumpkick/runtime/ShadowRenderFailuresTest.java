// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.FakeClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A failed shadow render is remembered for the module while its POM is unchanged and the window
 * has not passed, and forgotten the moment either changes.
 */
class ShadowRenderFailuresTest {

    private static Path module(Path tmp) throws IOException {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(module.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        return module;
    }

    @Test
    void the_failure_is_recalled_while_the_pom_stands_and_the_window_runs(@TempDir Path tmp) throws Exception {
        Path module = module(tmp);
        FakeClock clock = new FakeClock();
        ShadowRenderFailures failures = new ShadowRenderFailures(clock, 20_000);
        IOException stalled = new IOException("Resolution budget exceeded: no POM read advanced for 20 s");

        assertThat(failures.recall(module)).isEmpty();
        failures.remember(module, stalled);
        clock.advance(Duration.ofSeconds(19));

        assertThat(failures.recall(module)).contains(stalled);
        assertThat(failures.recall(tmp.resolve("other"))).isEmpty();
    }

    @Test
    void the_failure_expires_with_the_window(@TempDir Path tmp) throws Exception {
        Path module = module(tmp);
        FakeClock clock = new FakeClock();
        ShadowRenderFailures failures = new ShadowRenderFailures(clock, 20_000);
        failures.remember(module, new IOException("stalled"));

        clock.advance(Duration.ofSeconds(20));

        assertThat(failures.recall(module)).isEmpty();
        assertThat(failures.size()).as("the expired entry is dropped").isZero();
    }

    @Test
    void an_edited_pom_renders_afresh(@TempDir Path tmp) throws Exception {
        Path module = module(tmp);
        FakeClock clock = new FakeClock();
        ShadowRenderFailures failures = new ShadowRenderFailures(clock, 20_000);
        failures.remember(module, new IOException("stalled"));

        Path pom = module.resolve("pom.xml");
        Files.writeString(pom, "<project><!-- edited --></project>", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(
                pom, FileTime.fromMillis(Files.getLastModifiedTime(pom).toMillis() + 5_000));

        assertThat(failures.recall(module)).isEmpty();
    }
}
