// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.command.toolchain.ToolListCommand;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk storage nuke} against an isolated home, with the engine-side wipe stubbed by a local
 * delete: what the command does around the wipe — the installed tools it orphans — is this
 * process's own work and needs no engine.
 */
class StorageCommandTest {

    @TempDir
    Path isolatedHome;

    private String prevHome;
    private String prevState;
    private String prevStore;

    @BeforeEach
    void isolateHome() throws IOException {
        prevHome = System.getProperty("jk.env.JK_HOME");
        prevState = System.getProperty("jk.env.JK_STATE_DIR");
        prevStore = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_HOME", isolatedHome.toString());
        System.setProperty(
                "jk.env.JK_STATE_DIR",
                Files.createDirectories(isolatedHome.resolve("state")).toString());
        // The store the tests wipe is the isolated home's, whatever JK_STORE_DIR the shell exports.
        System.setProperty("jk.env.JK_STORE_DIR", isolatedHome.resolve("store").toString());
    }

    @AfterEach
    void restoreHome() {
        if (prevHome == null) System.clearProperty("jk.env.JK_HOME");
        else System.setProperty("jk.env.JK_HOME", prevHome);
        if (prevState == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevState);
        if (prevStore == null) System.clearProperty("jk.env.JK_STORE_DIR");
        else System.setProperty("jk.env.JK_STORE_DIR", prevStore);
    }

    /**
     * An installed tool execs jars under {@code <store>/sha256/…}. A store wipe that left its env
     * and launcher would keep it in {@code jk tool list} with a launcher that fails "could not find
     * or load main class"; the wipe takes both, names the tool at the confirm, and counts it on the
     * settle line — the same rule {@code jk self nuke --store} applies.
     */
    @Test
    void a_store_nuke_removes_the_tool_envs_it_orphans_and_their_launchers_and_says_so() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path store = dirs.storeDir();
        Path casJar = Files.createDirectories(store.resolve("sha256/ab")).resolve("widget.jar");
        Files.writeString(casJar, "jar");
        Path widget = installedTool(dirs, "widget", casJar);
        Path widgetCmd = Files.writeString(dirs.binDirectory().resolve("widget.cmd"), "@echo off\r\n");
        Path elsewhere = Files.writeString(isolatedHome.resolve("elsewhere.jar"), "jar");
        Path local = installedTool(dirs, "local", elsewhere);
        // Dispatch installs assume-yes around a leaf command; this drives the command body directly.
        Confirm.setAssumeYes(true);
        String out;
        try {
            out = TestAnsi.strip(captureText(() -> StorageCommand.runNuke(false, false, true, localWipe())));
        } finally {
            Confirm.clearAssumeYes();
        }

        assertThat(store).doesNotExist();
        assertThat(dirs.toolEnvsDir().resolve("widget")).doesNotExist();
        assertThat(widget).doesNotExist();
        assertThat(widgetCmd).doesNotExist();
        assertThat(local)
                .as("a tool whose classpath lies elsewhere keeps its launcher")
                .exists();
        assertThat(dirs.toolEnvsDir().resolve("local/env.json")).exists();
        assertThat(out)
                .as("the confirm names the tools the wipe orphans")
                .contains("Orphans 1 installed tool (widget)");
        String settle = out.lines().filter(l -> l.contains("Nuked")).findFirst().orElse("");
        assertThat(settle).contains("removed 1 orphaned tool (widget; env and launcher — jk install restores them)");
        String listed = TestAnsi.strip(captureText(() -> toolList(dirs)));
        assertThat(listed).contains("local").doesNotContain("widget");
    }

    @Test
    void a_dry_run_names_the_tools_the_wipe_would_orphan_and_leaves_them() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path store = dirs.storeDir();
        Path casJar = Files.createDirectories(store.resolve("sha256/ab")).resolve("widget.jar");
        Files.writeString(casJar, "jar");
        Path widget = installedTool(dirs, "widget", casJar);

        String out = TestAnsi.strip(captureText(() -> StorageCommand.runNuke(true, true, true, localWipe())));

        assertThat(store).exists();
        assertThat(widget).exists();
        assertThat(dirs.toolEnvsDir().resolve("widget/env.json")).exists();
        assertThat(out).contains("would remove 1 orphaned tool (widget;");
    }

    /**
     * The store leg of {@code jk self nuke --store} carries the orphaned tools in its own plan
     * table, so the shared nuke leaves them to it rather than sweeping them a second time.
     */
    @Test
    void the_self_nuke_leg_leaves_the_orphaned_tools_to_the_callers_table() throws Exception {
        JkDirs dirs = JkDirs.current();
        Path store = dirs.storeDir();
        Path casJar = Files.createDirectories(store.resolve("sha256/ab")).resolve("widget.jar");
        Files.writeString(casJar, "jar");
        Path widget = installedTool(dirs, "widget", casJar);

        String out = TestAnsi.strip(captureText(() -> StorageCommand.runNuke(false, true, false, localWipe())));

        assertThat(store).doesNotExist();
        assertThat(widget).exists();
        assertThat(dirs.toolEnvsDir().resolve("widget/env.json")).exists();
        assertThat(out).doesNotContain("orphaned tool");
    }

    /** The engine-side wipe as a local delete: counts under dry run, removes the root otherwise. */
    /** {@code --workers} names what went and what the next build does about it; a dry run says "would". */
    @Test
    void the_dropped_workers_line_names_each_worker_and_its_repo() {
        CacheInventoryAck dropped = CacheInventoryAck.droppedWorkers(
                List.of("jk-image-builder|0.13.3|jk-local", "jk-image-builder|0.13.2|jumpkick"), 4, 900_000);

        String line = StorageCommand.StorageCleanCommand.droppedWorkersLine(dropped, false);
        assertThat(line)
                .startsWith("Dropped 2 workers: jk-image-builder 0.13.3 (jk-local), jk-image-builder 0.13.2 (jumpkick)")
                .contains("4 files")
                .contains("The next build fetches the published plugin");
        assertThat(StorageCommand.StorageCleanCommand.droppedWorkersLine(dropped, true))
                .startsWith("Dry run: would drop 2 workers")
                .contains("reclaimable");
        assertThat(StorageCommand.StorageCleanCommand.droppedWorkersLine(
                        CacheInventoryAck.droppedWorkers(List.of(), 0, 0), false))
                .isEqualTo("No installed plugin workers to drop.");
    }

    private static StorageCommand.StoreWipe localWipe() {
        return (root, dryRun) -> {
            long files;
            long bytes = 0;
            try (Stream<Path> walk = Files.walk(root)) {
                var regular = walk.filter(Files::isRegularFile).toList();
                files = regular.size();
                for (Path p : regular) bytes += Files.size(p);
            }
            if (!dryRun) PathUtil.deleteRecursivelyOrThrow(root);
            return new long[] {files, bytes};
        };
    }

    /** {@code jk install <name>}'s footprint: the env under state recording {@code classpath}, and a launcher in bin. */
    private static Path installedTool(JkDirs dirs, String name, Path classpath) throws IOException {
        Path env = Files.createDirectories(dirs.toolEnvsDir().resolve(name));
        String jar = classpath.toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(env.resolve("env.json"), "{\"binName\": \"" + name + "\", \"classpath\": [\"" + jar + "\"]}");
        Path launcher = Files.createDirectories(dirs.binDirectory()).resolve(name);
        Files.writeString(launcher, "#!/usr/bin/env bash\nexec java -cp " + jar + " Main \"$@\"\n");
        return launcher;
    }

    private static int toolList(JkDirs dirs) throws IOException {
        return new ToolListCommand()
                .run(Invocation.builder()
                        .putValue("state-dir", dirs.stateDir().toString())
                        .putValue("bin-dir", dirs.binDirectory().toString())
                        .build());
    }

    private static String captureText(Callable<Integer> body) throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream cap = new PrintStream(buf, true, StandardCharsets.UTF_8);
        System.setOut(cap);
        System.setErr(cap);
        try {
            body.call();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
