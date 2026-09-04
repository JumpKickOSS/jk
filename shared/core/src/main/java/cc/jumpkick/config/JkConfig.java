// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * Immutable CLI-wide settings (color, offline, quiet, …) after merging file layers and env.
 * {@code null} means unset — use the built-in default, which the {@code …Or(fallback)} readers
 * below supply. CLI flags are applied by the caller after {@link JkConfigLoader#load}.
 *
 * <p>The components are {@code @Nullable T}, not {@code Optional<T>}: a record component is a field
 * plus an accessor, and the house rule is that {@code Optional} is a return type only. Twelve
 * {@code Optional} components cost twelve heap objects per config — one is built per file layer,
 * per env read and per CLI overlay — and, more to the point, they defeat {@link With}: a generated
 * wither takes the component's type, so every caller would have to box a value it already has.
 * Single-field copies are all {@code @With}-generated; a caller that wants the layered value asks
 * one of the {@code …Or} readers instead of unwrapping by hand.
 */
public record JkConfig(
        @With @Nullable ColorChoice color,
        @With @Nullable Boolean offline,
        /**
         * Bypass build caches without re-fetching locked deps. Implied by {@code force}.
         */
        @With @Nullable Boolean rebuild,
        @With @Nullable Boolean noProgress,
        @With @Nullable Boolean quiet,
        @With @Nullable Boolean verbose,
        @With @Nullable Path directory,
        /** Bypass all caching for this invocation (recompile, re-resolve, rerun tests). */
        @With @Nullable Boolean force,
        /**
         * Disable all ANSI (color and attributes). Distinct from {@code --color never}, which
         * strips color only.
         */
        @With @Nullable Boolean noAnsi,
        /**
         * Emit ANSI even where the environment would suppress it ({@code TERM=dumb}, {@code CI}).
         * {@code config.force-ansi} / {@code JK_FORCE_ANSI}. {@code noAnsi} still wins when both
         * are set: turning ANSI off is the safer direction to honor.
         */
        @With @Nullable Boolean forceAnsi,
        /**
         * Disable OSC capabilities (window title, taskbar progress, desktop notifications).
         * {@code --no-osc} / {@code config.no-osc} / {@code JK_NO_OSC}.
         */
        @With @Nullable Boolean noOsc,
        /**
         * Desktop notification policy for long builds ({@code config.notify} /
         * {@code --notify}/{@code --no-notify} / {@code JK_NOTIFY}).
         */
        @With @Nullable NotifyChoice notifyPolicy,
        /**
         * Open the live-plan process-output peek by default ({@code config.build-output} /
         * {@code JK_BUILD_OUTPUT}). Default {@code false}: hidden until Ctrl-O or a failed
         * tool/worker force-show.
         */
        @With @Nullable Boolean buildOutput) {

    public enum ColorChoice {
        AUTO,
        ALWAYS,
        NEVER;

        public static Optional<ColorChoice> parse(@Nullable String s) {
            if (s == null || s.isBlank()) return Optional.empty();
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> Optional.of(AUTO);
                case "always" -> Optional.of(ALWAYS);
                case "never" -> Optional.of(NEVER);
                default -> Optional.empty();
            };
        }
    }

    /**
     * Desktop notification policy. Default {@link #AUTO}: notify when ETA or elapsed ≥ 1 minute.
     * {@link #ALWAYS} / {@link #NEVER} force on/off (CLI {@code --notify}/{@code --no-notify}).
     * TOML accepts {@code "auto"|"always"|"never"} and booleans {@code true}/{@code false}.
     */
    public enum NotifyChoice {
        AUTO,
        ALWAYS,
        NEVER;

        public static Optional<NotifyChoice> parse(@Nullable String s) {
            if (s == null || s.isBlank()) return Optional.empty();
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> Optional.of(AUTO);
                case "always", "true", "yes", "on", "1" -> Optional.of(ALWAYS);
                case "never", "false", "no", "off", "0" -> Optional.of(NEVER);
                default -> Optional.empty();
            };
        }
    }

    /** Empty config — every setting unset. Used as the seed before layers merge. */
    public static JkConfig empty() {
        return new JkConfig(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Return a new config with {@code over}'s set values laid on top of this one's. {@code over} wins
     * where it has a value; otherwise this config's value passes through.
     */
    public JkConfig mergedWith(JkConfig over) {
        return new JkConfig(
                set(over.color, color),
                set(over.offline, offline),
                set(over.rebuild, rebuild),
                set(over.noProgress, noProgress),
                set(over.quiet, quiet),
                set(over.verbose, verbose),
                set(over.directory, directory),
                set(over.force, force),
                set(over.noAnsi, noAnsi),
                set(over.forceAnsi, forceAnsi),
                set(over.noOsc, noOsc),
                set(over.notifyPolicy, notifyPolicy),
                set(over.buildOutput, buildOutput));
    }

    /** {@code over} when it is set, else {@code under}. Both may be unset. */
    private static <T> @Nullable T set(@Nullable T over, @Nullable T under) {
        return over != null ? over : under;
    }

    /** Convenience: color with a fallback when empty. */
    public ColorChoice colorOr(ColorChoice fallback) {
        return color != null ? color : fallback;
    }

    public boolean offlineOr(boolean fallback) {
        return offline != null ? offline : fallback;
    }

    /** True when {@code -F}/{@code --force} / {@code JK_FORCE} was set for this invocation. */
    public boolean forceOr(boolean fallback) {
        return force != null ? force : fallback;
    }

    /**
     * True when this build must bypass jk's own caches ({@code force} implies it). NOT "the first of
     * {@code force}, {@code rebuild} that is set": wire decodes materialize {@code force} as an
     * explicit {@code false}, which silently masked a set-and-true {@code rebuild}. Either being
     * true is true; only both being unset defers to {@code fallback}.
     */
    public boolean rebuildOr(boolean fallback) {
        if (force == null && rebuild == null) return fallback;
        return Boolean.TRUE.equals(force) || Boolean.TRUE.equals(rebuild);
    }

    /** True when {@code --no-ansi} was set — all ANSI sequences suppressed, ASCII only. */
    public boolean noAnsiOr(boolean fallback) {
        return noAnsi != null ? noAnsi : fallback;
    }

    /**
     * True when ANSI was forced on — outranks the {@code TERM=dumb}/{@code CI} suppressors, so a
     * test (or a user in CI) can pin the mode instead of inheriting it.
     */
    public boolean forceAnsiOr(boolean fallback) {
        return forceAnsi != null ? forceAnsi : fallback;
    }

    /** True when {@code --no-osc} was set — no window title, taskbar progress, or notifications. */
    public boolean noOscOr(boolean fallback) {
        return noOsc != null ? noOsc : fallback;
    }

    public boolean noProgressOr(boolean fallback) {
        return noProgress != null ? noProgress : fallback;
    }

    public boolean quietOr(boolean fallback) {
        return quiet != null ? quiet : fallback;
    }

    public boolean verboseOr(boolean fallback) {
        return verbose != null ? verbose : fallback;
    }

    /** The {@code -C}/{@code --directory} / {@code config.directory} root, or {@code fallback}. */
    public Path directoryOr(Path fallback) {
        return directory != null ? directory : fallback;
    }

    /** Notify policy with fallback (default {@link NotifyChoice#AUTO}). */
    public NotifyChoice notifyOr(NotifyChoice fallback) {
        return notifyPolicy != null ? notifyPolicy : fallback;
    }

    /**
     * True when live-plan process output should start open ({@code config.build-output} /
     * {@code JK_BUILD_OUTPUT}). Default {@code false}.
     */
    public boolean buildOutputOr(boolean fallback) {
        return buildOutput != null ? buildOutput : fallback;
    }
}
