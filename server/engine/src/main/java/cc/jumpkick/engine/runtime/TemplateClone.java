// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** The {@code git clone} a remote template ref runs, bounded by a timeout that always holds. */
final class TemplateClone {

    static final Duration TIMEOUT = Duration.ofSeconds(120);

    record Result(int exit, String output) {}

    private TemplateClone() {}

    /**
     * Runs {@code command} with git's terminal prompts off and returns its exit code and merged
     * output. The output drains on its own platform thread, so the timeout holds for a git that
     * neither exits nor closes its pipe (a stalled network, a credential helper waiting on input);
     * a timed-out git is killed with its descendants.
     */
    static Result run(List<String> command, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        // The request's shell and nothing of the engine's own: cleared, then set.
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(BuildEnv.callerEnv());
        env.put("GIT_TERMINAL_PROMPT", "0");
        Processes.Captured c = Processes.capture(JobWorkers.start(pb), timeout);
        if (c.timedOut()) throw new IOException("git clone timed out after " + timeout.toSeconds() + "s");
        return new Result(c.exit(), c.output());
    }
}
