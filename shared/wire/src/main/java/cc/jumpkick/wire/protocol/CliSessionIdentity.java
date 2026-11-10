// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.ProcessEnv;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Who is running this {@code jk} process, as the engine's session log names it: one terminal tab,
 * one agent, one script. Every run a CLI process sends carries the {@link #label} as its {@code
 * session} and the {@link #leader} beside it, so two terminals are two sessions on the Console rail
 * and a session ends when the process behind it exits.
 *
 * <p>Most specific first:
 *
 * <ol>
 *   <li>an agent's own session id from its environment ({@code CLAUDE_CODE_SESSION_ID}, {@code
 *       CODEX_THREAD_ID}): every shell an agent's tool spawns is one session, {@code claude-code
 *       (shell) 4c1e9a}, led by the agent's process when the environment names it ({@code
 *       CLAUDE_PID});
 *   <li>on Linux, the session leader {@code /proc/self/stat} names, with its controlling tty: a
 *       terminal tab or SSH connection runs its shell as the leader of a session of its own, while
 *       every tab of one terminal emulator shares a parent, so the parent is never the key;
 *   <li>elsewhere (macOS, Windows), the outermost shell in the unbroken run of shells above this
 *       process: the tab's shell, past any script it ran {@code jk} from.
 * </ol>
 *
 * <p>The label is {@code <client> <key>}: the client says what kind of actor it is ({@code
 * terminal}, {@code script} when there is no tty, {@code claude-code (shell)}), and the key is six
 * hex digits of a hash over the identity, so no pid or tty reaches the dashboard. The leader is
 * {@code <pid>@<start, epoch millis>}, which the engine probes and never shows. A process started
 * with an explicit session ({@code JK_BUILD_SESSION}, a BSP server's declaration) never reaches
 * here.
 */
public final class CliSessionIdentity {

    /** The identity: the label runs carry, and the leader process whose exit ends the session, when one is known. */
    public record Identity(String label, @Nullable String leader) {}

    /** What the identity is read from: the environment, {@code /proc/self/stat}, and the process tree. */
    interface Probe {
        @Nullable
        String env(String name);

        /** The contents of {@code /proc/self/stat}, or null off Linux or when it will not read. */
        @Nullable
        String procSelfStat();

        Optional<ProcessHandle> process(long pid);

        ProcessHandle self();
    }

    /** Shell executables, lower-case and without {@code .exe}: the run of these above jk is the terminal's. */
    static final Set<String> SHELLS = Set.of(
            "sh",
            "bash",
            "zsh",
            "fish",
            "dash",
            "ksh",
            "mksh",
            "tcsh",
            "csh",
            "nu",
            "elvish",
            "xonsh",
            "pwsh",
            "powershell",
            "cmd");

    private static volatile @Nullable Identity current;

    private CliSessionIdentity() {}

    /** This process's identity, worked out once. */
    public static Identity current() {
        Identity id = current;
        if (id == null) {
            id = of(SYSTEM);
            current = id;
        }
        return id;
    }

    static Identity of(Probe probe) {
        Identity agent = agent(probe);
        if (agent != null) return agent;
        Identity linux = linux(probe);
        if (linux != null) return linux;
        return ancestry(probe);
    }

    private static @Nullable Identity agent(Probe probe) {
        String claude = nonBlank(probe.env("CLAUDE_CODE_SESSION_ID"));
        if (claude != null) {
            return new Identity(
                    label("claude-code (shell)", "claude-code|" + claude), leaderOf(probe, probe.env("CLAUDE_PID")));
        }
        String codex = nonBlank(probe.env("CODEX_THREAD_ID"));
        if (codex != null) return new Identity(label("codex (shell)", "codex|" + codex), null);
        return null;
    }

    /** The session leader and tty {@code /proc/self/stat} names; null off Linux or when it names no leader. */
    private static @Nullable Identity linux(Probe probe) {
        String stat = probe.procSelfStat();
        if (stat == null) return null;
        int close = stat.lastIndexOf(')');
        if (close < 0) return null;
        // After "(comm)": state ppid pgrp session tty_nr ...
        String[] f = stat.substring(close + 1).strip().split("\\s+");
        if (f.length < 5) return null;
        long sid;
        long tty;
        try {
            sid = Long.parseLong(f[3]);
            tty = Long.parseLong(f[4]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (sid <= 0) return null;
        Optional<ProcessHandle> leader = probe.process(sid);
        long started = leader.map(CliSessionIdentity::startMillis).orElse(0L);
        String client = tty != 0 ? "terminal" : "script";
        return new Identity(
                label(client, "sid|" + sid + "|" + started + "|" + tty),
                leader.isPresent() ? sid + "@" + started : null);
    }

    /** The outermost shell in the unbroken run of shells above this process, else its parent. */
    private static Identity ancestry(Probe probe) {
        ProcessHandle self = probe.self();
        ProcessHandle outermost = null;
        ProcessHandle p = self.parent().orElse(null);
        // jk's own launcher (a script, a JVM wrapper) may sit between the shell and this process.
        for (int hops = 0; p != null && hops < 16; hops++) {
            if (isShell(p)) outermost = p;
            else if (outermost != null) break;
            p = p.parent().orElse(null);
        }
        ProcessHandle leader = outermost != null ? outermost : self.parent().orElse(self);
        long started = startMillis(leader);
        boolean tty = System.console() != null || nonBlank(probe.env("WT_SESSION")) != null;
        return new Identity(
                label(tty ? "terminal" : "script", "pid|" + leader.pid() + "|" + started),
                leader.pid() + "@" + started);
    }

    static boolean isShell(ProcessHandle p) {
        String command = p.info().command().orElse(null);
        if (command == null) return false;
        String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        if (name.startsWith("-")) name = name.substring(1);
        return SHELLS.contains(name);
    }

    private static @Nullable String leaderOf(Probe probe, @Nullable String pid) {
        if (pid == null || pid.isBlank()) return null;
        try {
            long n = Long.parseLong(pid.strip());
            return probe.process(n).map(h -> n + "@" + startMillis(h)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static long startMillis(ProcessHandle p) {
        return p.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    /** {@code <client> <six hex digits of sha-256(material)>}. */
    static String label(String client, String material) {
        return client + " " + Hashing.sha256Hex(material).substring(0, 6);
    }

    private static @Nullable String nonBlank(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static final Probe SYSTEM = new Probe() {
        @Override
        public @Nullable String env(String name) {
            return ProcessEnv.get(name);
        }

        @Override
        public @Nullable String procSelfStat() {
            Path stat = Path.of("/proc/self/stat");
            try {
                return Files.isReadable(stat) ? Files.readString(stat, StandardCharsets.UTF_8) : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public Optional<ProcessHandle> process(long pid) {
            return ProcessHandle.of(pid);
        }

        @Override
        public ProcessHandle self() {
            return ProcessHandle.current();
        }
    };

    /** For tests that build a probe from a map. */
    static Probe probe(Function<String, @Nullable String> env, @Nullable String stat) {
        return new Probe() {
            @Override
            public @Nullable String env(String name) {
                return env.apply(name);
            }

            @Override
            public @Nullable String procSelfStat() {
                return stat;
            }

            @Override
            public Optional<ProcessHandle> process(long pid) {
                return ProcessHandle.of(pid);
            }

            @Override
            public ProcessHandle self() {
                return ProcessHandle.current();
            }
        };
    }
}
