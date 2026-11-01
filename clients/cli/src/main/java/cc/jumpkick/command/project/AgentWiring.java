// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a new or imported project gets for its coding agents: {@link AgentSetup}'s files for the
 * agents in use ({@code JK_AGENTS=all|none} overrides detection) and a first code graph, so the
 * first symbol question is answered without an index wait.
 */
public final class AgentWiring {

    private AgentWiring() {}

    /** Wire {@code dir} and report what was written on stderr. */
    public static void report(Path dir) throws IOException {
        for (String line : run(dir)) CliOutput.err(line);
    }

    /**
     * Wire {@code dir}; the lines say what was written or skipped, none when all was current. A
     * failure to seed the graph never fails the caller.
     */
    public static List<String> run(Path dir) throws IOException {
        return run(dir, AgentSetup.Mode.parse(System.getenv("JK_AGENTS")));
    }

    /** As {@link #run(Path)} for an explicit {@code mode}. */
    public static List<String> run(Path dir, AgentSetup.Mode mode) throws IOException {
        List<String> lines = AgentSetup.install(dir, mode, hosts());
        boolean project = Files.isRegularFile(ManifestPaths.manifestIn(dir));
        if (mode != AgentSetup.Mode.NONE
                && project
                && !Files.isDirectory(dir.resolve(".jk").resolve("graph"))) {
            seedGraph(dir);
        }
        return lines;
    }

    /** Take jk's entries out of {@code dir}; the lines say what changed. */
    public static List<String> remove(Path dir) throws IOException {
        return AgentSetup.remove(dir);
    }

    /** The agents on this machine: Claude Code's shell, and every agent CLI on {@code PATH}. */
    static Set<AgentSetup.Host> hosts() {
        Set<AgentSetup.Host> found = EnumSet.noneOf(AgentSetup.Host.class);
        String claudeCode = System.getenv("CLAUDECODE");
        if (claudeCode != null && !claudeCode.isEmpty()) found.add(AgentSetup.Host.CLAUDE);
        List<String> entries = SearchPath.entries(System.getenv("PATH"));
        for (AgentSetup.Host host : AgentSetup.Host.values()) {
            for (String entry : entries) {
                Path dir = SearchPath.path(entry);
                if (dir != null && PathUtil.isRunnable(dir.resolve(host.cli))) {
                    found.add(host);
                    break;
                }
            }
        }
        return found;
    }

    private static void seedGraph(Path dir) {
        try {
            GraphRequest request = new GraphRequest(
                    dir.toString(),
                    "build",
                    List.of(),
                    "bfs",
                    3,
                    2000,
                    List.of(),
                    false,
                    8,
                    null,
                    false,
                    false,
                    null,
                    null,
                    null,
                    List.of());
            GraphAck ack = EngineClient.graph(EnginePaths.current(), request, progress -> {});
            if (!ack.ok()) CliOutput.err("code graph not seeded: " + ack.error());
        } catch (RuntimeException | IOException e) {
            CliOutput.err("code graph not seeded: " + e.getMessage());
        }
    }
}
