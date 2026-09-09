// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.concurrent.atomic.AtomicReference;
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
        // Prefix plus containment, not equality — same contract as the exit-code message: what
        // went wrong, then which invocation. A stalled fetch that does not name the repository is
        // the whole reason this message carries the command (JK-2959).
        assertTrue(
                e.getMessage().startsWith("git timed out after 2s"),
                () -> "expected the timeout and its budget up front, got: " + e.getMessage());
        assertTrue(
                e.getMessage().contains(script.toString()),
                () -> "expected the stalled command to be named, got: " + e.getMessage());
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

    /**
     * The two failures nothing used to cover. {@code runGit} discards the subprocess's output at
     * the OS level, so its {@code IOException} message is the only thing a caller ever sees; every
     * way out of it has to name the invocation, not just the one that happened to have a test.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runGitNamesTheCommandWhenItCannotStart() {
        Path missing = tmp.resolve("no-such-git-binary");
        IOException e =
                assertThrows(IOException.class, () -> OfficialTemplatesFreshen.runGit(List.of(missing.toString()), 10));
        assertTrue(
                e.getMessage().startsWith("git not on PATH"),
                () -> "expected the not-on-PATH reason up front, got: " + e.getMessage());
        assertTrue(
                e.getMessage().contains(missing.toString()),
                () -> "expected the command to be named, got: " + e.getMessage());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runGitNamesTheCommandWhenTheWaitIsInterrupted() throws Exception {
        Path script = script("#!/bin/sh\nsleep 600\n");
        AtomicReference<IOException> caught = new AtomicReference<>();
        // A generous timeout, so the only way out is the interrupt — not the timeout branch.
        Thread waiter = new Thread(() -> {
            try {
                OfficialTemplatesFreshen.runGit(List.of(script.toString()), 600);
            } catch (IOException e) {
                caught.set(e);
            }
        });
        waiter.start();
        waiter.interrupt();
        waiter.join(30_000);
        assertTrue(!waiter.isAlive(), "runGit did not settle after the interrupt");

        IOException e = caught.get();
        assertTrue(e != null, "expected runGit to surface the interrupt as an IOException");
        assertTrue(
                e.getMessage().startsWith("git interrupted"),
                () -> "expected the interrupt reason up front, got: " + e.getMessage());
        assertTrue(
                e.getMessage().contains(script.toString()),
                () -> "expected the command to be named, got: " + e.getMessage());
    }

    /**
     * The exit code, and which invocation produced it. {@code 3d05952e3} added the command to this
     * message because a bare "git exit 128" from a MAX_PATH clone failure named nothing you could
     * act on — so the command is part of the contract, not incidental text. Asserted as a prefix
     * plus a containment rather than the whole string, so adding more context cannot break it again.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runGitSurfacesNonZeroExitAndTheCommandThatFailed() throws Exception {
        Path script = script("#!/bin/sh\nexit 3\n");
        IOException e =
                assertThrows(IOException.class, () -> OfficialTemplatesFreshen.runGit(List.of(script.toString()), 10));
        assertTrue(
                e.getMessage().startsWith("git exit 3"),
                () -> "expected the exit code up front, got: " + e.getMessage());
        assertTrue(
                e.getMessage().contains(script.toString()),
                () -> "expected the failing command to be named, got: " + e.getMessage());
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
        // Shallow on purpose: the cache root only has to sit inside the enclosing repository for
        // this test to mean anything, and a deep mirror of a real jk home spends MAX_PATH budget the
        // clone underneath it needs.
        Path cacheRoot = enclosing.resolve("tpl");
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

    @Test
    void a_real_source_key_is_unchanged_and_stays_readable() {
        // The official catalog and anything like it must keep the name it already has on disk, or
        // every existing cache directory is orphaned and re-cloned on upgrade.
        assertEquals(
                "github.com_jumpkickoss_jk-templates",
                OfficialTemplatesFreshen.parse("https://github.com/JumpKickOSS/jk-templates.git")
                        .cacheKey());
    }

    @Test
    void a_long_source_key_is_bounded_and_still_unique() {
        String deep = "file:///C:/Users/someone/src/oss/jk-some-worktree/shared/core/build/tmp/"
                + "junit-17705283888040414576/official/";
        String other = deep.replace("official", "another");

        String a = OfficialTemplatesFreshen.parse(deep).cacheKey();
        String b = OfficialTemplatesFreshen.parse(other).cacheKey();

        assertTrue(a.length() <= 60, "key is bounded, was " + a.length() + ": " + a);
        assertTrue(b.length() <= 60, "key is bounded, was " + b.length() + ": " + b);
        assertNotEquals(a, b, "two long sources must not collide once truncated");
        assertEquals(a, OfficialTemplatesFreshen.parse(deep).cacheKey(), "the key is deterministic");
        assertTrue(a.startsWith("file_c_users_someone"), "the readable prefix survives: " + a);
    }

    @Test
    void a_pinned_rev_still_separates_long_sources() {
        String deep =
                "file:///C:/Users/someone/src/oss/jk-some-worktree/shared/core/build/tmp/junit-1770528388/official/";
        String a = OfficialTemplatesFreshen.parse(deep + "#v1").cacheKey();
        String b = OfficialTemplatesFreshen.parse(deep + "#v2").cacheKey();
        assertNotEquals(a, b, "a pin is part of the identity even when the key is truncated");
        assertTrue(a.length() <= 60 && b.length() <= 60);
    }
}
