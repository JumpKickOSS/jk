// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.StoreWipe;
import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Invocation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk install} shelves a checkout-built jk's workers and built-in plugins under {@code
 * repos/jk-local}, and no repository has a {@code -SNAPSHOT} of them: a nuke that took them left a
 * jk that failed every build configuring {@code [intellij]}, JumpKick's own included, so it could
 * not run the install that puts them back. They stay; the rest of the store goes.
 */
class SelfNukeShelfTest {

    @TempDir
    Path isolatedHome;

    @BeforeEach
    void isolateHome() throws IOException {
        System.setProperty("jk.env.JK_HOME", isolatedHome.toString());
        System.setProperty(
                "jk.env.JK_STATE_DIR",
                Files.createDirectories(isolatedHome.resolve("state")).toString());
        System.setProperty("jk.env.JK_STORE_DIR", isolatedHome.resolve("store").toString());
    }

    /** A store nuke whose engine wipes as the real one does, then takes a late write before the stop. */
    private record WipesLikeTheEngine(Path store) implements SelfNukeCommand.Hosted {
        @Override
        public int storage() throws IOException {
            StoreWipe.wipe(store);
            Files.writeString(
                    Files.createDirectories(store.resolve("sha256/zz")).resolve("late"), "late");
            return 0;
        }

        @Override
        public int cache(Path cacheDir, boolean dryRun, GlobalOptions global, boolean enginesStopped) {
            return 0;
        }
    }

    @Test
    void a_store_nuke_keeps_this_jks_own_shelf_and_says_so() throws Exception {
        Path store = JkDirs.current().storeDir();
        String version = JkVersion.VERSION;
        Path ownJar = write(
                store.resolve("repos/jk-local/cc/jumpkick/jk-intellij/" + version + "/jk-intellij-" + version + ".jar"),
                "plugin");
        Path olderJar =
                write(store.resolve("repos/jk-local/cc/jumpkick/jk-intellij/0.0.1/jk-intellij-0.0.1.jar"), "old");
        Path blob = write(store.resolve("sha256/ab/blob"), "cas");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = System.out;
        PrintStream err = System.err;
        PrintStream cap = new PrintStream(buf, true, StandardCharsets.UTF_8);
        System.setOut(cap);
        System.setErr(cap);
        Confirm.setAssumeYes(true);
        int exit;
        try {
            Invocation in =
                    Invocation.builder().flag("yes", true).flag("store", true).build();
            exit = new SelfNukeCommand().run(in, new WipesLikeTheEngine(store));
        } finally {
            Confirm.clearAssumeYes();
            System.setOut(out);
            System.setErr(err);
        }
        String text = TestAnsi.strip(buf.toString(StandardCharsets.UTF_8));

        assertThat(exit).as(text).isZero();
        assertThat(ownJar).as("the running jk's own plugin jar").hasContent("plugin");
        assertThat(olderJar).doesNotExist();
        assertThat(blob).doesNotExist();
        assertThat(store.resolve("sha256")).as("the late write the sweep takes").doesNotExist();
        assertThat(text)
                .contains("Kept:")
                .contains("jk-local")
                .contains(version + "  (this jk's own 1 shelved artifact; no repository has them)");
    }

    private static Path write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }
}
