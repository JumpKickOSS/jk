// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.tui.JkManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The lock's live handler with no live region — {@code --no-progress}, or stdout a pipe — prints
 * what the engine sends as it arrives: the lock's phase lines between the per-package completion
 * lines, so a transcript of a solve that runs for minutes shows it moving.
 */
class LockCommandLiveHandlerTest {

    @Test
    void without_a_live_region_the_locks_phase_lines_print_as_they_arrive() {
        ByteArrayOutputStream region = new ByteArrayOutputStream();
        JkManager view = JkManager.plan(new PrintStream(region, true, StandardCharsets.UTF_8), "Lock", false);
        LockCommand.LiveLockHandler handler = new LockCommand.LiveLockHandler(view);

        String out = Capture.stdout(() -> {
                    handler.onModuleStart("/w/app", "com.example:app", List.of());
                    handler.onPhase("/w/app", "Resolving dependency graph… 120 packages so far, 10s");
                    handler.onPackage("/w/app", "com.foo:leaf", "1.0", 1);
                    handler.onPhase("/w/app", "Downloading 1 artifacts…");
                })
                .replaceAll("\u001b\\[[\\d;]*m", "");

        assertThat(out).contains("Resolving dependency graph").contains("120 packages so far, 10s");
        assertThat(out).contains("com.foo:leaf").contains("Downloading 1 artifacts");
        assertThat(out.indexOf("120 packages so far"))
                .as("the heartbeat precedes the package it was followed by")
                .isLessThan(out.indexOf("com.foo:leaf"));
        assertThat(out.indexOf("com.foo:leaf")).isLessThan(out.indexOf("Downloading 1 artifacts"));
        assertThat(region.toString(StandardCharsets.UTF_8))
                .as("no region was drawn")
                .doesNotContain("120 packages");
    }
}
