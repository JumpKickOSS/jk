// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.tui.ChangeSummary;
import cc.jumpkick.docs.AgentSetup;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.layout.GraphLayout;
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
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * What a new or imported project gets for its coding agents: {@link AgentSetup}'s files for the
 * agents in use ({@code JK_AGENTS=all|none} overrides detection) and a first code graph, so the
 * first symbol question is answered without an index wait.
 */
public final class AgentWiring {

    /**
     * Where {@code JK_AGENTS}, {@code CLAUDECODE} and {@code PATH} are read: the process environment
     * unless a caller bound another (tests stand an agent up without changing the JVM's environment).
     */
    static final ScopedValue<UnaryOperator<@Nullable String>> ENV = ScopedValue.newInstance();

    private AgentWiring() {}

    private static @Nullable String env(String name) {
        return ENV.isBound() ? ENV.get().apply(name) : System.getenv(name);
    }

    /** Wire {@code dir} and print what it wrote and the steps left to the user. */
    public static void report(Path dir) throws IOException {
        print(run(dir), dir, "Agent notes");
    }

    /** {@code report}'s files as a tree under {@code dir}'s name, then its notes numbered under {@code title}. */
    public static void print(AgentSetup.Report report, Path dir, String title) {
        ChangeSummary.printChanges(ChangeSummary.name(dir), changes(report), dir);
        ChangeSummary.printNotes(title, "note", report.notes());
        if (!report.changes().isEmpty() || !report.notes().isEmpty()) CliOutput.out("");
    }

    /** {@code report}'s files as the summary renders them. */
    public static List<ChangeSummary.Change> changes(AgentSetup.Report report) {
        return report.changes().stream()
                .map(c -> new ChangeSummary.Change(c.action(), c.file()))
                .toList();
    }

    /**
     * Wire {@code dir}; the report says what was written and what is left to the user. A failure to
     * seed the graph never fails the caller.
     */
    public static AgentSetup.Report run(Path dir) throws IOException {
        return run(dir, AgentSetup.Mode.parse(env("JK_AGENTS")));
    }

    /** As {@link #run(Path)} for an explicit {@code mode}. */
    public static AgentSetup.Report run(Path dir, AgentSetup.Mode mode) throws IOException {
        AgentSetup.Report lines = AgentSetup.install(dir, mode, hosts());
        boolean project = Files.isRegularFile(ManifestPaths.manifestIn(dir));
        if (mode != AgentSetup.Mode.NONE && project && !GraphLayout.built(dir)) {
            seedGraph(dir);
        }
        return lines;
    }

    /** Take jk's entries out of {@code dir}; the report says what changed. */
    public static AgentSetup.Report remove(Path dir) throws IOException {
        return AgentSetup.remove(dir);
    }

    /** The agents on this machine: Claude Code's shell, and every agent CLI on {@code PATH}. */
    static Set<AgentSetup.Host> hosts() {
        Set<AgentSetup.Host> found = EnumSet.noneOf(AgentSetup.Host.class);
        String claudeCode = env("CLAUDECODE");
        if (claudeCode != null && !claudeCode.isEmpty()) found.add(AgentSetup.Host.CLAUDE);
        List<String> entries = SearchPath.entries(env("PATH"));
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
