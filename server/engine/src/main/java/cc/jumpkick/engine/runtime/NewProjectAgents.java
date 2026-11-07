// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import cc.jumpkick.docs.AgentChoice;
import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ProcessEnv;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The agent wiring of a new project. {@code jk new} sends the agents setting of the shell it runs
 * in; MCP {@code new} and the dashboard's wizard have no shell of their own, so they take the
 * engine's ({@code JK_AGENTS}, and the agents its environment shows).
 */
public final class NewProjectAgents {

    private static volatile @Nullable Function<String, @Nullable String> env;

    private NewProjectAgents() {}

    /** Tests: the environment the engine's agents setting is read from; null restores the process's. */
    public static void env(@Nullable Function<String, @Nullable String> override) {
        env = override;
    }

    /** The agents setting a create made through the engine's own surfaces wires for. */
    public static AgentChoice current() {
        Function<String, @Nullable String> e = env;
        return AgentChoice.from(e != null ? e : ProcessEnv::get);
    }

    /**
     * Wire {@code dir} for {@code choice}. The project exists already, so a failure is a note, not a
     * failed create.
     */
    static AgentSetup.Report wire(Path dir, AgentChoice choice) {
        try {
            return choice.install(dir);
        } catch (IOException | RuntimeException e) {
            Log.debug("new project: agent wiring failed", e);
            return new AgentSetup.Report(List.of(), List.of("Agent files not written: " + Errors.text(e)));
        }
    }

    /** {@code report}'s files by their path under {@code dir}, then its notes, as a create answers them. */
    public static Map<String, Object> json(AgentSetup.Report report, Path dir) {
        List<String> files = new ArrayList<>();
        for (AgentSetup.Change c : report.changes()) {
            files.add(dir.relativize(c.file()).toString().replace('\\', '/'));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", files);
        out.put("notes", report.notes());
        return out;
    }
}
