// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Which agents a project is wired for: the user's {@code JK_AGENTS} setting and the agents found on
 * the machine. Every surface that creates a project settles one and hands it to the create, so a
 * project made by {@code jk new}, MCP {@code new} or the dashboard's wizard gets the same files.
 *
 * @param mode {@code JK_AGENTS}: {@code all}, {@code none}, else detection
 * @param hosts the agents present: the calling agent's shell, and every agent CLI on {@code PATH}
 */
public record AgentChoice(AgentSetup.Mode mode, Set<AgentSetup.Host> hosts) {

    public AgentChoice {
        hosts = hosts.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(hosts));
    }

    /** The choice {@code env} makes: its {@code JK_AGENTS}, {@code CLAUDECODE} and {@code PATH}. */
    public static AgentChoice from(Function<String, @Nullable String> env) {
        return new AgentChoice(AgentSetup.Mode.parse(env.apply("JK_AGENTS")), detect(env));
    }

    /** As {@link #from} for a mode and hosts carried as their lowercase names; an unknown host is dropped. */
    public static AgentChoice parse(@Nullable String mode, List<String> hosts) {
        Set<AgentSetup.Host> found = EnumSet.noneOf(AgentSetup.Host.class);
        for (String h : hosts) {
            for (AgentSetup.Host host : AgentSetup.Host.values()) {
                if (host.name().equalsIgnoreCase(h.strip())) found.add(host);
            }
        }
        return new AgentChoice(AgentSetup.Mode.parse(mode), found);
    }

    /**
     * This choice with {@code host} present too: the agent that asked for the project is wired
     * whatever else the machine shows. {@link AgentSetup.Mode#NONE} still wires none.
     */
    public AgentChoice with(AgentSetup.Host host) {
        if (hosts.contains(host)) return this;
        Set<AgentSetup.Host> more = EnumSet.noneOf(AgentSetup.Host.class);
        more.addAll(hosts);
        more.add(host);
        return new AgentChoice(mode, more);
    }

    /** {@link #mode} as {@link #parse} reads it. */
    public String modeName() {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /** {@link #hosts} as {@link #parse} reads them. */
    public List<String> hostNames() {
        List<String> out = new ArrayList<>();
        for (AgentSetup.Host h : hosts) out.add(h.name().toLowerCase(Locale.ROOT));
        return out;
    }

    /** {@link AgentSetup#install} into {@code dir} for this choice. */
    public AgentSetup.Report install(Path dir) throws IOException {
        return AgentSetup.install(dir, mode, hosts);
    }

    /** The agents {@code env} shows: the calling agent's shell, and every agent CLI on its {@code PATH}. */
    static Set<AgentSetup.Host> detect(Function<String, @Nullable String> env) {
        Set<AgentSetup.Host> found = EnumSet.noneOf(AgentSetup.Host.class);
        String claudeCode = env.apply("CLAUDECODE");
        if (claudeCode != null && !claudeCode.isEmpty()) found.add(AgentSetup.Host.CLAUDE);
        List<String> entries = SearchPath.entries(env.apply("PATH"));
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
}
