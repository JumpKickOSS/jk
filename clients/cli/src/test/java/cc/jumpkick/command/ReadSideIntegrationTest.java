// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static cc.jumpkick.cli.testing.MockMavenServer.pom;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the full plan: init -> add -> lock -> tree / why / sync. */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class ReadSideIntegrationTest {

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @BeforeEach
    void seedRepo() {
        DefaultTestDepsFixture.seed(maven.served());
    }

    @Test
    void full_pipeline_init_add_lock_tree_why_sync(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo", "leaf", "1.0");
        maven.registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        maven.registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        maven.registerMetadata("com.foo", "root", "1.0");
        maven.registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        maven.registerJar("com.foo", "root", "1.0", "root".getBytes(StandardCharsets.UTF_8));

        Path cache = tempDir.resolve("cache");

        run("new", tempDir.toString());
        run("add", "com.foo:root:1.0", "-C", tempDir.toString());
        run("lock", "-C", tempDir.toString(), "--repo-url", maven.base().toString(), "--cache-dir", cache.toString());

        // jk tree — strip ANSI escapes so the GAV-formatted labels
        // line up as plain substrings the assertions can match
        // against. --color=never drops the foreground colors but
        // leaves text attributes (underline/bold) in place, hence
        // the regex below.
        String declared = TestAnsi.strip(Capture.stdout(() -> run("tree", "-C", tempDir.toString())));
        assertThat(declared).contains("com.foo:root:1.0");
        assertThat(declared).doesNotContain("com.foo:leaf:1.0");

        String tree = TestAnsi.strip(Capture.stdout(() -> run("tree", "-t", "-C", tempDir.toString())));
        assertThat(tree).contains("com.foo:root:1.0");
        assertThat(tree).contains("com.foo:leaf:1.0");

        // jk why
        String why = TestAnsi.strip(Capture.stdout(() -> run("why", "com.foo:leaf", "-C", tempDir.toString())));
        assertThat(why).contains("com.foo:leaf:1.0 is pulled in by:");
        assertThat(why).contains("com.foo:root:1.0");
        // Each step says what its parent asked for: the manifest for the root, root's POM for leaf.
        assertThat(why).contains("by jk.toml)").contains("(declared 1.0 by com.foo:root)");

        // jk sync — second time with cache populated should report up-to-date.
        String sync = Capture.stdout(() -> run("sync", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(sync).contains("up-to-date");

        // jk sync with a fresh ACTION cache must NOT re-fetch: since the cache/store split the
        // artifacts live in the store, and not re-downloading on cache isolation is the split's
        // whole point (root + leaf + the two defaulted JUnit coords stay up-to-date).
        Path freshCache = tempDir.resolve("fresh-cache");
        Files.createDirectories(freshCache);
        String resync =
                Capture.stdout(() -> run("sync", "-C", tempDir.toString(), "--cache-dir", freshCache.toString()));
        assertThat(resync).contains("4 up-to-date");
    }

    @Test
    void why_returns_1_for_unknown_module(@TempDir Path tempDir) throws IOException {
        // The queried module isn't in the (empty) lock — jk new no longer writes
        // one, so stand in the empty lock a first build would produce.
        run("new", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir);
        int exit = run("why", "com.foo:bar", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void tree_without_lockfile_freshens_one_invisibly(@TempDir Path tempDir) throws IOException {
        // Write a jk.toml by hand so no jk-lock.toml exists. Since aa655a0f the
        // invisible freshen WRITES a missing lock instead of erroring — tree succeeds and
        // the lock exists afterwards.
        Files.writeString(tempDir.resolve("jk.toml"), "group = \"com.example\"\nname = \"a\"\nversion = \"0.1.0\"\n");
        int exit = run("tree", "-C", tempDir.toString());
        assertThat(exit).isZero();
        assertThat(tempDir.resolve("jk-lock.toml")).exists();
    }

    @Test
    void sync_creates_lockfile_when_missing(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo", "leaf", "1.0");
        maven.registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        maven.registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        run("new", tempDir.toString());
        run("add", "com.foo:leaf:1.0", "-C", tempDir.toString());
        // Erase the empty lockfile that `jk init` stamps so we can verify
        // sync creates a fresh one (with the dep we just added).
        Path lockFile = tempDir.resolve("jk-lock.toml");
        Files.deleteIfExists(lockFile);

        String out = Capture.stdout(() -> run(
                "sync",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--repo-url",
                maven.base().toString()));

        assertThat(lockFile).exists();
        // sync auto-locks when jk-lock.toml is missing, then reports its summary.
        assertThat(out.replaceAll("\\u001B\\[[;0-9]*m", "")).contains("Sync");
        // The package we added should show up in the freshly written lock.
        assertThat(Files.readString(lockFile)).contains("com.foo:leaf");
    }

    @Test
    void sync_accepts_offline_prepare_flag(@TempDir Path tempDir) throws Exception {
        maven.registerMetadata("com.foo", "leaf", "1.0");
        maven.registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        maven.registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        run("new", tempDir.toString());
        run("add", "com.foo:leaf:1.0", "-C", tempDir.toString());
        run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());

        int exit = run(
                "sync",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("fresh").toString(),
                "--offline-prepare");
        assertThat(exit).isEqualTo(0);
    }
}
