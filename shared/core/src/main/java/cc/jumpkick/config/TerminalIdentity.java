// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Which terminal is drawing this session, from the environment alone.
 *
 * <p>Signals are read in order of how reliably they name the <em>outermost</em> terminal.
 * {@code TERM_PROGRAM} wins when it names a terminal; multiplexers overwrite it ({@code tmux}), so
 * for them the terminal-specific variables the multiplexer's panes inherit are consulted next, then
 * {@code TERM}. A remote session sees only what ssh forwards, so only {@code TERM} and
 * {@code LC_TERMINAL} count there; the other variables describe the server, not the client.
 */
public record TerminalIdentity(Terminal terminal, boolean multiplexed, boolean remote, String version) {

    /** Terminals jk tells apart. {@link #UNKNOWN} is anything else. */
    public enum Terminal {
        GHOSTTY("ghostty"),
        KITTY("kitty"),
        WEZTERM("wezterm"),
        WINDOWS_TERMINAL("windows-terminal"),
        ITERM2("iterm2"),
        APPLE_TERMINAL("apple-terminal"),
        ALACRITTY("alacritty"),
        VSCODE("vscode"),
        ZED("zed"),
        KONSOLE("konsole"),
        VTE("vte"),
        FOOT("foot"),
        RIO("rio"),
        WARP("warp"),
        TABBY("tabby"),
        HYPER("hyper"),
        UNKNOWN("unknown");

        private final String id;

        Terminal(String id) {
            this.id = id;
        }

        /** Stable lowercase token, used in cache keys and {@code --explain}. */
        public String id() {
            return id;
        }
    }

    /** Identity of the real process environment. */
    public static TerminalIdentity current() {
        return of(BuildEnv::get);
    }

    /** Identity under an injected environment. */
    public static TerminalIdentity of(Function<String, @Nullable String> env) {
        String termProgram = lower(env.apply("TERM_PROGRAM"));
        String term = lower(env.apply("TERM"));
        boolean multiplexed = set(env, "TMUX")
                || set(env, "STY")
                || set(env, "ZELLIJ")
                || termProgram.equals("tmux")
                || term.startsWith("screen")
                || term.startsWith("tmux");
        boolean remote = set(env, "SSH_TTY") || set(env, "SSH_CONNECTION");

        if (remote) {
            Terminal t = fromTerm(term);
            if (t == Terminal.UNKNOWN && lower(env.apply("LC_TERMINAL")).equals("iterm2")) t = Terminal.ITERM2;
            String version = t == Terminal.ITERM2 ? trim(env.apply("LC_TERMINAL_VERSION")) : "";
            return new TerminalIdentity(t, multiplexed, true, version);
        }

        Terminal t = fromTermProgram(termProgram);
        String version = t == Terminal.UNKNOWN ? "" : trim(env.apply("TERM_PROGRAM_VERSION"));
        if (t == Terminal.UNKNOWN) {
            t = fromMarkers(env);
            if (t == Terminal.UNKNOWN) t = fromTerm(term);
            version = markerVersion(t, env);
        }
        return new TerminalIdentity(t, multiplexed, false, version);
    }

    private static Terminal fromTermProgram(String p) {
        return switch (p) {
            case "ghostty" -> Terminal.GHOSTTY;
            case "kitty" -> Terminal.KITTY;
            case "wezterm" -> Terminal.WEZTERM;
            case "iterm.app" -> Terminal.ITERM2;
            case "apple_terminal" -> Terminal.APPLE_TERMINAL;
            case "alacritty" -> Terminal.ALACRITTY;
            case "vscode" -> Terminal.VSCODE;
            case "zed" -> Terminal.ZED;
            case "rio" -> Terminal.RIO;
            case "warpterminal" -> Terminal.WARP;
            case "tabby" -> Terminal.TABBY;
            case "hyper" -> Terminal.HYPER;
            default -> Terminal.UNKNOWN;
        };
    }

    /** Variables a terminal exports to its children; they survive a multiplexer in the pane. */
    private static Terminal fromMarkers(Function<String, @Nullable String> env) {
        if (set(env, "GHOSTTY_RESOURCES_DIR") || set(env, "GHOSTTY_BIN_DIR")) return Terminal.GHOSTTY;
        if (set(env, "KITTY_WINDOW_ID") || set(env, "KITTY_PID")) return Terminal.KITTY;
        if (set(env, "WEZTERM_EXECUTABLE") || set(env, "WEZTERM_PANE")) return Terminal.WEZTERM;
        if (set(env, "WT_SESSION")) return Terminal.WINDOWS_TERMINAL;
        if (set(env, "ITERM_SESSION_ID")) return Terminal.ITERM2;
        if (set(env, "ALACRITTY_LOG") || set(env, "ALACRITTY_WINDOW_ID") || set(env, "ALACRITTY_SOCKET")) {
            return Terminal.ALACRITTY;
        }
        if (set(env, "KONSOLE_VERSION")) return Terminal.KONSOLE;
        if (set(env, "VTE_VERSION")) return Terminal.VTE;
        return Terminal.UNKNOWN;
    }

    private static Terminal fromTerm(String term) {
        if (term.equals("xterm-ghostty")) return Terminal.GHOSTTY;
        if (term.equals("xterm-kitty")) return Terminal.KITTY;
        if (term.startsWith("alacritty")) return Terminal.ALACRITTY;
        if (term.startsWith("foot")) return Terminal.FOOT;
        if (term.startsWith("wezterm")) return Terminal.WEZTERM;
        return Terminal.UNKNOWN;
    }

    private static String markerVersion(Terminal t, Function<String, @Nullable String> env) {
        return switch (t) {
            case KONSOLE -> trim(env.apply("KONSOLE_VERSION"));
            case VTE -> trim(env.apply("VTE_VERSION"));
            default -> "";
        };
    }

    private static boolean set(Function<String, @Nullable String> env, String name) {
        String v = env.apply(name);
        return v != null && !v.isBlank();
    }

    private static String lower(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(@Nullable String s) {
        return s == null ? "" : s.trim();
    }
}
