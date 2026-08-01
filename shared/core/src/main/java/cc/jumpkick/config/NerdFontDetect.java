// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;
import java.util.function.Function;

/**
 * Best-effort Nerd Font / PUA-glyph capability probe. Intended for <strong>install-time
 * / setup-command</strong> use — write the result to {@code ~/.jk/config.toml}, do not call on every
 * build. Env {@code JK_NERDFONT} still overrides at runtime.
 */
public final class NerdFontDetect {

    private NerdFontDetect() {}

    public record Result(boolean nerdFont, String reason) {}

    /** Probe using the process environment. */
    public static Result detect() {
        return detect(System::getenv);
    }

    /** Probe over an env lookup, consulting the real installed-font list. */
    public static Result detect(Function<String, String> env) {
        return detect(env, NerdFontDetect::fontListLooksNerdy);
    }

    /**
     * Fully injectable probe: {@code env} for the terminal hints, {@code fontProbe} for "is a Nerd
     * Font installed".
     *
     * <p>The font probe has to be injectable for any of this to be testable. It shells out to {@code
     * fc-list}, so its answer is a property of the developer's machine, not of the code — asserting
     * that an unknown terminal defaults to <em>off</em> passed on CI (no fonts installed) and failed
     * on any workstation with a Nerd Font, which is precisely backwards from what a test should key
     * on.
     */
    public static Result detect(Function<String, String> env, java.util.function.BooleanSupplier fontProbe) {
        String forced = env.apply("JK_NERDFONT");
        if (forced != null && !forced.isBlank()) {
            boolean on = EnvValues.parseBool(forced).orElse(false);
            return new Result(on, "JK_NERDFONT=" + forced.trim());
        }
        // CI / dumb terminals never want PUA
        String ci = env.apply("CI");
        if ("true".equalsIgnoreCase(ci) || "1".equals(ci)) {
            return new Result(false, "CI environment");
        }
        if ("dumb".equals(env.apply("TERM"))) {
            return new Result(false, "TERM=dumb");
        }

        String termProgram = nullToEmpty(env.apply("TERM_PROGRAM")).toLowerCase(Locale.ROOT);
        // Known good hosts for powerline/nerd glyphs when users often install patched fonts
        if (termProgram.contains("iterm")
                || termProgram.contains("wezterm")
                || termProgram.contains("warp")
                || termProgram.contains("ghostty")
                || termProgram.contains("alacritty")
                || termProgram.contains("hyper")) {
            return new Result(true, "TERM_PROGRAM=" + termProgram);
        }
        // VS Code integrated terminal often has a nerd-capable font by default in recent builds
        // still conservative: only if TERM_PROGRAM=vscode
        if (termProgram.contains("vscode") || termProgram.contains("cursor")) {
            return new Result(true, "TERM_PROGRAM=" + termProgram);
        }
        // Windows Terminal
        if (env.apply("WT_SESSION") != null && !env.apply("WT_SESSION").isBlank()) {
            return new Result(true, "Windows Terminal (WT_SESSION)");
        }
        // Apple Terminal.app — system font is not nerd by default
        if (termProgram.contains("apple_terminal") || termProgram.equals("terminal")) {
            return new Result(false, "Terminal.app (no nerd font by default)");
        }

        // Optional: fc-list hint (Linux) — only when PATH allows; ignore failures
        if (fontProbe.getAsBoolean()) {
            return new Result(true, "fc-list matched a Nerd Font name");
        }

        // Unknown → prefer no PUA (safer than tofu)
        return new Result(false, "unknown terminal — defaulting to no PUA glyphs");
    }

    private static boolean fontListLooksNerdy() {
        try {
            Process p = new ProcessBuilder("fc-list", ":family")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0) return false;
            String lower = out.toLowerCase(Locale.ROOT);
            return lower.contains("nerd")
                    || lower.contains("meslo")
                    || lower.contains("caskaydia")
                    || lower.contains("jetbrainsmono nerd")
                    || lower.contains("fira code");
        } catch (Exception e) {
            return false;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
