// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class OfficialTemplatesFreshenTest {

    @TempDir
    Path tmp;

    /** A subprocess that never exits must be killed within the timeout, not awaited to EOF. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runGitKillsHungSubprocessWithinTimeout() throws Exception {
        // Holds stdout open and sleeps forever — the old readAllBytes() path would block here.
        Path script = script("#!/bin/sh\nsleep 600\n");
        long start = System.nanoTime();
        IOException e =
                assertThrows(IOException.class, () -> OfficialTemplatesFreshen.runGit(List.of(script.toString()), 2));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals("git timed out", e.getMessage());
        assertTrue(elapsedMs < 30_000, "timeout not enforced: took " + elapsedMs + "ms");
    }

    /** Large output must not deadlock the pipe (discarded at the OS level). */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runGitDiscardsLargeOutputWithoutDeadlock() throws Exception {
        // 8 MB of output overflows any pipe buffer if unread.
        Path script = script("#!/bin/sh\ndd if=/dev/zero bs=1024 count=8192 2>/dev/null\nexit 0\n");
        OfficialTemplatesFreshen.runGit(List.of(script.toString()), 30);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runGitSurfacesNonZeroExit() throws Exception {
        Path script = script("#!/bin/sh\nexit 3\n");
        IOException e =
                assertThrows(IOException.class, () -> OfficialTemplatesFreshen.runGit(List.of(script.toString()), 10));
        assertEquals("git exit 3", e.getMessage());
    }

    /** One git attempt per cache key per TTL — success or failure — so a missing
     * short name on an offline host can't re-run a 60–120s git attempt on every retry. */
    @Test
    void sourceRefEncodesUrlAndRev() {
        var plain = new cc.jumpkick.config.JkTemplatesConfig.Source(
                "acme", "https://github.com/acme/jk-g8", Optional.empty());
        var pinned = new cc.jumpkick.config.JkTemplatesConfig.Source(
                "corp", "https://git.example/corp/jk-templates.git", Optional.of("main"));
        org.assertj.core.api.Assertions.assertThat(OfficialTemplatesFreshen.sourceRef(plain))
                .isEqualTo("https://github.com/acme/jk-g8");
        org.assertj.core.api.Assertions.assertThat(OfficialTemplatesFreshen.sourceRef(pinned))
                .isEqualTo("https://git.example/corp/jk-templates.git#main");
        // Distinct cache dirs per source — a rev pin never shadows the unpinned clone.
        org.assertj.core.api.Assertions.assertThat(
                        OfficialTemplatesFreshen.parse(OfficialTemplatesFreshen.sourceRef(pinned))
                                .cacheKey())
                .isEqualTo("git.example_corp_jk-templates_main")
                .isNotEqualTo(OfficialTemplatesFreshen.parse(OfficialTemplatesFreshen.sourceRef(plain))
                        .cacheKey());
    }

    @Test
    void attemptGuardAllowsFirstBlocksWithinTtlAndReopensAfter() {
        OfficialTemplatesFreshen.resetAttemptGuardForTests();
        long t0 = 1_000L;
        assertTrue(OfficialTemplatesFreshen.markAttempt("key-a", t0));
        assertTrue(
                !OfficialTemplatesFreshen.markAttempt("key-a", t0 + OfficialTemplatesFreshen.ATTEMPT_TTL_NANOS - 1),
                "second attempt within the TTL must be suppressed");
        assertTrue(OfficialTemplatesFreshen.markAttempt("key-b", t0), "keys are independent");
        assertTrue(
                OfficialTemplatesFreshen.markAttempt("key-a", t0 + OfficialTemplatesFreshen.ATTEMPT_TTL_NANOS),
                "attempt slot reopens after the TTL");
        OfficialTemplatesFreshen.resetAttemptGuardForTests();
    }

    private Path script(String body) throws IOException {
        Path script = tmp.resolve("fake-git.sh");
        Files.writeString(script, body);
        Files.setPosixFilePermissions(
                script,
                EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        return script;
    }
}
