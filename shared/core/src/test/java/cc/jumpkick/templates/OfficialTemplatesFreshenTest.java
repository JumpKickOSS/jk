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
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
        // Holds stdout open and sleeps forever.
        Path script = script("#!/bin/sh\nsleep 600\n");
        long start = System.nanoTime();
        IOException e =
                assertThrows(IOException.class, () -> OfficialTemplatesFreshen.runGit(List.of(script.toString()), 2));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Prefix plus containment, not equality — same contract as the exit-code message: what
        // went wrong, then which invocation.
        String message = String.valueOf(e.getMessage());
        assertTrue(
                message.startsWith("git timed out after 2s"),
                () -> "expected the timeout and its budget up front, got: " + message);
        assertTrue(
                message.contains(script.toString()), () -> "expected the stalled command to be named, got: " + message);
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
        String message = String.valueOf(e.getMessage());
        assertTrue(
                message.startsWith("git not on PATH"),
                () -> "expected the not-on-PATH reason up front, got: " + message);
        assertTrue(message.contains(missing.toString()), () -> "expected the command to be named, got: " + message);
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

        IOException e =
                Objects.requireNonNull(caught.get(), "expected runGit to surface the interrupt as an IOException");
        String message = String.valueOf(e.getMessage());
        assertTrue(
                message.startsWith("git interrupted"), () -> "expected the interrupt reason up front, got: " + message);
        assertTrue(message.contains(script.toString()), () -> "expected the command to be named, got: " + message);
    }

    /**
     * The exit code, which invocation produced it, and what git said on stderr. Asserted as a prefix
     * plus containments rather than the whole string, so more context cannot break it.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runGitSurfacesNonZeroExitTheCommandAndGitsStderr() throws Exception {
        Path script = script("#!/bin/sh\necho 'progress noise' >&2\necho 'fatal: repository not found' >&2\nexit 3\n");
        IOException e =
                assertThrows(IOException.class, () -> OfficialTemplatesFreshen.runGit(List.of(script.toString()), 10));
        String message = String.valueOf(e.getMessage());
        assertTrue(message.startsWith("git exit 3"), () -> "expected the exit code up front, got: " + message);
        assertTrue(
                message.contains(script.toString()), () -> "expected the failing command to be named, got: " + message);
        assertTrue(
                message.endsWith("fatal: repository not found"),
                () -> "expected git's stderr to end the message, got: " + message);
    }

    /** A hung transport child must die with the git it belongs to, or the stalled connection lives on. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runGitKillsTheDescendantsOfATimedOutGit() throws Exception {
        // 599, not 600: a marker no other test's sleeper carries.
        Path script = script("#!/bin/sh\nsleep 599 &\nwait\n");
        assertThrows(IOException.class, () -> OfficialTemplatesFreshen.runGit(List.of(script.toString()), 1));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sleeper599Alive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(!sleeper599Alive(), "the sleeper git spawned outlived the kill");
    }

    private static boolean sleeper599Alive() {
        return ProcessHandle.allProcesses().anyMatch(h -> {
            ProcessHandle.Info info = h.info();
            return info.command().map(c -> c.endsWith("sleep")).orElse(false)
                    && info.arguments()
                            .map(a -> a.length == 1 && a[0].equals("599"))
                            .orElse(false);
        });
    }

    /** The quiet paths log every failure, cut to a width a log line can carry. */
    @Test
    void aQuietFailureIsLoggedOnOneLineHoweverLongItsMessage() {
        String argv = "git exit 128: git clone --depth 1 https://example.invalid/x.git " + "/very/long/".repeat(60)
                + " — fatal: unable to access";
        String line = OfficialTemplatesFreshen.skipped(new IOException(argv));

        assertTrue(line.startsWith("jk engine: templates freshen skipped (IOException: git exit 128: git clone"), line);
        assertTrue(line.length() <= OfficialTemplatesFreshen.SKIPPED_WIDTH + 64, () -> "too wide: " + line.length());
        assertEquals(
                "jk engine: templates freshen skipped (IllegalStateException)",
                OfficialTemplatesFreshen.skipped(new IllegalStateException()));
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

    /**
     * The state the check cannot cover: {@code dest} is not a repository at the moment the fetch and
     * reset run. {@link OfficialTemplatesFreshen#isRepositoryRoot} passing and then the directory going
     * away — a sandbox teardown, a sibling test JVM, a {@code clean} — is the window the check cannot
     * close.
     *
     * <p>Calling the refresh directly is the point: through {@code refreshRef} the check would route
     * this to a clone and the invocations would never run. What has to hold is that they are pinned to a
     * named repository, so a missing one fails the command rather than choosing the enclosing one.
     *
     * <p>The enclosing repository is a full stand-in for a developer's checkout — an {@code origin} it
     * is behind, and an uncommitted edit. Without the remote the fetch fails for the wrong reason and
     * the test passes whether the invocations are pinned or not.
     */
    @Test
    void a_refresh_of_a_vanished_cache_fails_instead_of_resetting_the_enclosing_repository() throws Exception {
        Path upstream = tmp.resolve("upstream");
        Files.createDirectories(upstream);
        Files.writeString(upstream.resolve("work.txt"), "committed\n");
        git(upstream, "init", "-q", "-b", "main");
        git(upstream, "add", ".");
        git(upstream, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "first");

        Path enclosing = tmp.resolve("checkout");
        git(tmp, "clone", "-q", upstream.toUri().toString(), enclosing.toString());
        String headBefore = gitOut(enclosing, "rev-parse", "HEAD");

        // Upstream moves on, so a reset to FETCH_HEAD would be a visible move, and the developer has
        // work in the tree that such a reset would take with it.
        Files.writeString(upstream.resolve("work.txt"), "upstream moved\n");
        git(upstream, "add", ".");
        git(upstream, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "second");
        Files.writeString(enclosing.resolve("work.txt"), "edited, never committed\n");

        // Inside the enclosing repository, catalog-shaped, and not a repository of its own — what is
        // left once the clone that passed the check is deleted underneath it.
        Path dest = enclosing.resolve("tpl/github.com_jumpkickoss_jk-templates");
        Files.createDirectories(dest.resolve("java/spring/stale.g8"));
        assertTrue(!OfficialTemplatesFreshen.isRepositoryRoot(dest), "the fixture is not a repository");

        assertThrows(
                IOException.class,
                () -> OfficialTemplatesFreshen.fetchAndReset(dest, null),
                "a refresh of something that is not a repository must fail, not pick another one");

        assertEquals(headBefore, gitOut(enclosing, "rev-parse", "HEAD"), "the enclosing branch did not move");
        assertEquals(
                "edited, never committed\n",
                Files.readString(enclosing.resolve("work.txt")),
                "the uncommitted edit survived");
        assertTrue(
                !Files.exists(enclosing.resolve(".git/shallow")), "the enclosing repository was not shallow-fetched");
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
    void a_real_source_key_is_its_host_and_path() {
        // The bound only fires past MAX_CACHE_KEY; a real source keys as its readable host and path.
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
