// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link JobBreakawayTest}'s stand-in for a client: once told to (a line on stdin, after the test
 * has put it in a job), it starts a sleeping child the way the engine is started ({@code breakaway})
 * or with a plain {@link ProcessBuilder} ({@code plain}), prints the child's pid and how it was
 * started, and waits to be killed with its job.
 */
public final class BreakawayParentMain {

    private BreakawayParentMain() {}

    public static void main(String[] args) throws Exception {
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(
                        List.of(java, "-cp", System.getProperty("java.class.path"), SleeperMain.class.getName()))
                .redirectErrorStream(true)
                .redirectOutput(
                        ProcessBuilder.Redirect.appendTo(Path.of(args[1]).toFile()));
        Process child = args[0].equals("breakaway")
                ? JobBreakaway.start(pb, Path.of(args[1]), true, ProcessBuilder::start)
                : pb.start();
        System.out.println(
                "child " + child.pid() + " " + child.getClass().getSimpleName() + " " + JobBreakaway.fellBack);
        System.out.flush();
        Thread.sleep(120_000);
    }

    /** The child: sleeps until killed. */
    public static final class SleeperMain {
        private SleeperMain() {}

        public static void main(String[] args) throws Exception {
            Thread.sleep(120_000);
        }
    }
}
