// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.config.JkTemplatesConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
        // LIVENESS, not performance: the script sleeps 600s and runGit was given a 2s timeout, so
        // anything under 30s proves the timeout fired rather than the read blocking to EOF.
        assertTrue(
                elapsedMs < 30_000,
                "LIVENESS: the 2s git timeout did not fire — the call took " + elapsedMs
                        + "ms against a helper that sleeps 600s");
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
        var plain = new JkTemplatesConfig.Source("acme", "https://github.com/acme/jk-g8");
        var pinned = new JkTemplatesConfig.Source("corp", "https://git.example/corp/jk-templates.git", "main");
        org.assertj.core.api.Assertions.assertThat(plain.gitRef()).isEqualTo("https://github.com/acme/jk-g8");
        org.assertj.core.api.Assertions.assertThat(pinned.gitRef())
                .isEqualTo("https://git.example/corp/jk-templates.git#main");
        // Distinct cache dirs per source — a rev pin never shadows the unpinned clone.
        org.assertj.core.api.Assertions.assertThat(
                        OfficialTemplatesFreshen.parse(pinned.gitRef()).cacheKey())
                .isEqualTo("git.example_corp_jk-templates_main")
                .isNotEqualTo(OfficialTemplatesFreshen.parse(plain.gitRef()).cacheKey());
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

    /**
     * A catalog-shaped cache directory that is not a repository of its own is re-cloned, never
     * fetched into: {@code git -C} on such a directory acts on the repository enclosing it, and a
     * shallow fetch plus a hard reset there once wiped jk's own checkout from a test sandbox seeded
     * under the source tree. The enclosing repository keeps its head and stays unshallow.
     */
    @Test
    void a_catalog_directory_without_its_own_repository_is_recloned_not_fetched_into() throws Exception {
        Path official = tmp.resolve("official");
        Files.createDirectories(official.resolve("java/spring/hello.g8"));
        Files.writeString(official.resolve("java/spring/hello.g8/default.properties"), "name=hello\n");
        git(official, "init", "-q");
        git(official, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "seed");
        git(official, "add", ".");
        git(official, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "catalog");

        Path enclosing = tmp.resolve("checkout");
        Files.createDirectories(enclosing);
        Files.writeString(enclosing.resolve("work.txt"), "the user's work\n");
        git(enclosing, "init", "-q");
        git(enclosing, "add", ".");
        git(enclosing, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "user work");
        String headBefore = gitOut(enclosing, "rev-parse", "HEAD");

        String ref = official.toUri().toString();
        Path cacheRoot = enclosing.resolve("target/test-jk-home/store/templates");
        Path dest = cacheRoot.resolve(OfficialTemplatesFreshen.parse(ref).cacheKey());
        Files.createDirectories(dest.resolve("java/spring/stale.g8"));
        assertTrue(OfficialTemplatesFreshen.looksLikeTemplateMonorepo(dest), "the fixture is catalog-shaped");
        assertTrue(!OfficialTemplatesFreshen.isRepositoryRoot(dest), "but not a repository of its own");

        OfficialTemplatesFreshen.refreshRef(ref, cacheRoot, s -> {});

        assertEquals(headBefore, gitOut(enclosing, "rev-parse", "HEAD"), "the enclosing checkout is untouched");
        assertTrue(Files.isRegularFile(enclosing.resolve("work.txt")), "the working copy is untouched");
        assertTrue(
                !Files.exists(enclosing.resolve(".git/shallow")), "the enclosing repository was not shallow-fetched");
        assertTrue(OfficialTemplatesFreshen.isRepositoryRoot(dest), "the cache is now a clone of its own");
        assertTrue(Files.isDirectory(dest.resolve("java/spring/hello.g8")), "with the official catalog in it");
    }

    private static void git(Path dir, String... args) throws Exception {
        gitOut(dir, args);
    }

    private static String gitOut(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (proc.waitFor() != 0) throw new IOException("git " + String.join(" ", args) + " failed: " + out);
        return out.strip();
    }
}
