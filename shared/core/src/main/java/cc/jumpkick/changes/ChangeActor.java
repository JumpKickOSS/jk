// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.changes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Who made a change: a command typed in a shell, an agent's MCP session, an import, the engine on its
 * own, or the dashboard. A process names its default once ({@code jk} names the command line, the
 * engine names itself); a narrower scope (one MCP tool call, one import) overrides it for the work it
 * wraps through {@link #as}.
 *
 * @param kind one of {@link #SHELL}, {@link #AGENT}, {@link #IMPORT}, {@link #ENGINE}, {@link
 *     #DASHBOARD}
 * @param label one line a person reads: {@code jk config set …}, {@code Cursor session 3f2a…}
 * @param details the parts behind the label, for filters: {@code session}, {@code client}, {@code
 *     goal}, {@code tool}, {@code source}, {@code by}
 */
public record ChangeActor(String kind, String label, Map<String, String> details) {

    public static final String SHELL = "shell";
    public static final String AGENT = "agent";
    public static final String IMPORT = "import";
    public static final String ENGINE = "engine";
    public static final String DASHBOARD = "dashboard";

    private static final ScopedValue<ChangeActor> SCOPED = ScopedValue.newInstance();

    private static volatile ChangeActor processDefault = new ChangeActor(ENGINE, "JumpKick", Map.of());

    public ChangeActor {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(label);
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** The actor of the work running now: the innermost {@link #as} scope, else the process default. */
    public static ChangeActor current() {
        return SCOPED.isBound() ? SCOPED.get() : processDefault;
    }

    /** Name the actor of everything this process changes outside a narrower scope. */
    public static void setProcessDefault(ChangeActor actor) {
        processDefault = Objects.requireNonNull(actor);
    }

    /** Work that answers a value and may throw {@code X}. */
    @FunctionalInterface
    public interface Work<T extends @Nullable Object, X extends Throwable> {
        T get() throws X;
    }

    /** Run {@code work} with {@code actor} as the actor of every change it records. */
    public static <T extends @Nullable Object, X extends Throwable> T as(ChangeActor actor, Work<T, X> work) throws X {
        return ScopedValue.where(SCOPED, actor).call(work::get);
    }

    /**
     * The command line {@code jk args…} as an actor. Option values are left out, since one may be a
     * password: the words before the first option are kept (the command and its operands), then only
     * the option names.
     */
    public static ChangeActor shell(List<String> args) {
        List<String> kept = new ArrayList<>();
        kept.add("jk");
        boolean options = false;
        for (String arg : args) {
            if (arg.startsWith("-")) {
                options = true;
                if (arg.equals("--")) break;
                int eq = arg.indexOf('=');
                kept.add(eq < 0 ? arg : arg.substring(0, eq));
            } else if (!options) {
                kept.add(arg);
            }
        }
        String line = String.join(" ", kept);
        return new ChangeActor(SHELL, line, Map.of("command", line));
    }

    /**
     * An agent's MCP tool call: {@code client} is the host's name ({@code Cursor}, {@code Claude
     * Code}), {@code goal} the goal the call or its session named.
     */
    public static ChangeActor agent(
            @Nullable String session, @Nullable String client, @Nullable String goal, String tool) {
        Map<String, String> d = new LinkedHashMap<>();
        if (session != null) d.put("session", session);
        if (client != null) d.put("client", client);
        if (goal != null && !goal.isBlank()) d.put("goal", goal);
        d.put("tool", tool);
        String who = client == null || client.isBlank() ? "An agent" : client;
        String label = who + " (" + tool + (goal == null || goal.isBlank() ? "" : ": " + goal) + ")";
        return new ChangeActor(AGENT, label, d);
    }

    /** An import of {@code source} (a build file or project directory), on behalf of the current actor. */
    public static ChangeActor importOf(String source) {
        ChangeActor by = current();
        Map<String, String> d = new LinkedHashMap<>(by.details);
        d.put("source", source);
        d.put("by", by.label);
        d.put("byKind", by.kind);
        return new ChangeActor(IMPORT, "import of " + source + " by " + by.label, d);
    }

    /** The dashboard, acting on what a person clicked. */
    public static ChangeActor dashboard(String action) {
        return new ChangeActor(DASHBOARD, "the dashboard (" + action + ")", Map.of("action", action));
    }

    /** The journal's shape: {@code {kind, label, …details}}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("label", label);
        details.forEach(m::putIfAbsent);
        return m;
    }

    /** The actor a journal line holds, or null when it holds none. */
    static @Nullable ChangeActor of(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        if (!(m.get("kind") instanceof String kind) || !(m.get("label") instanceof String label)) return null;
        Map<String, String> d = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (!"kind".equals(k) && !"label".equals(k) && v instanceof String s) d.put(String.valueOf(k), s);
        });
        return new ChangeActor(kind, label, d);
    }
}
