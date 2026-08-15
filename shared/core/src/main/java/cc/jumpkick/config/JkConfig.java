// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable CLI-wide settings (color, offline, quiet, …) after merging file layers and env.
 * Empty {@link Optional} means unset — use the built-in default. CLI flags are applied by the
 * caller after {@link JkConfigLoader#load}.
 */
public record JkConfig(
        Optional<ColorChoice> color,
        Optional<Boolean> offline,
        /**
         * Bypass build caches without re-fetching locked deps. Implied by {@code force}.
         */
        Optional<Boolean> rebuild,
        Optional<Boolean> noProgress,
        Optional<Boolean> quiet,
        Optional<Boolean> verbose,
        Optional<Path> directory,
        /** Bypass all caching for this invocation (recompile, re-resolve, rerun tests). */
        Optional<Boolean> force,
        /**
         * Disable all ANSI (color and attributes). Distinct from {@code --color never}, which
         * strips color only.
         */
        Optional<Boolean> noAnsi,
        /**
         * Disable OSC capabilities (window title, taskbar progress, desktop notifications).
         * {@code --no-osc} / {@code config.no-osc} / {@code JK_NO_OSC}.
         */
        Optional<Boolean> noOsc,
        /**
         * Desktop notification policy for long builds ({@code config.notify} /
         * {@code --notify}/{@code --no-notify} / {@code JK_NOTIFY}).
         */
        Optional<NotifyChoice> notifyPolicy) {

    public enum ColorChoice {
        AUTO,
        ALWAYS,
        NEVER;

        public static Optional<ColorChoice> parse(String s) {
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

        public static Optional<NotifyChoice> parse(String s) {
            if (s == null || s.isBlank()) return Optional.empty();
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> Optional.of(AUTO);
                case "always", "true", "yes", "on", "1" -> Optional.of(ALWAYS);
                case "never", "false", "no", "off", "0" -> Optional.of(NEVER);
                default -> Optional.empty();
            };
        }
    }

    public JkConfig {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(offline, "offline");
        Objects.requireNonNull(noProgress, "noProgress");
        Objects.requireNonNull(quiet, "quiet");
        Objects.requireNonNull(verbose, "verbose");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(force, "force");
        Objects.requireNonNull(noAnsi, "noAnsi");
        Objects.requireNonNull(noOsc, "noOsc");
        Objects.requireNonNull(notifyPolicy, "notifyPolicy");
    }

    /** Empty config — every setting unset. Used as the seed before layers merge. */
    public static JkConfig empty() {
        return new JkConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    // --- withers: one-field copies so callers (tests especially) never restate the
    // 11-positional-Optional constructor. ---

    public JkConfig withColor(Optional<ColorChoice> v) {
        return new JkConfig(
                v, offline, rebuild, noProgress, quiet, verbose, directory, force, noAnsi, noOsc, notifyPolicy);
    }

    public JkConfig withOffline(Optional<Boolean> v) {
        return new JkConfig(
                color, v, rebuild, noProgress, quiet, verbose, directory, force, noAnsi, noOsc, notifyPolicy);
    }

    public JkConfig withRebuild(Optional<Boolean> v) {
        return new JkConfig(
                color, offline, v, noProgress, quiet, verbose, directory, force, noAnsi, noOsc, notifyPolicy);
    }

    public JkConfig withNoProgress(Optional<Boolean> v) {
        return new JkConfig(color, offline, rebuild, v, quiet, verbose, directory, force, noAnsi, noOsc, notifyPolicy);
    }

    public JkConfig withQuiet(Optional<Boolean> v) {
        return new JkConfig(
                color, offline, rebuild, noProgress, v, verbose, directory, force, noAnsi, noOsc, notifyPolicy);
    }

    public JkConfig withVerbose(Optional<Boolean> v) {
        return new JkConfig(
                color, offline, rebuild, noProgress, quiet, v, directory, force, noAnsi, noOsc, notifyPolicy);
    }

    public JkConfig withDirectory(Optional<Path> v) {
        return new JkConfig(color, offline, rebuild, noProgress, quiet, verbose, v, force, noAnsi, noOsc, notifyPolicy);
    }

    public JkConfig withForce(Optional<Boolean> v) {
        return new JkConfig(
                color, offline, rebuild, noProgress, quiet, verbose, directory, v, noAnsi, noOsc, notifyPolicy);
    }

    public JkConfig withNoAnsi(Optional<Boolean> v) {
        return new JkConfig(
                color, offline, rebuild, noProgress, quiet, verbose, directory, force, v, noOsc, notifyPolicy);
    }

    public JkConfig withNoOsc(Optional<Boolean> v) {
        return new JkConfig(
                color, offline, rebuild, noProgress, quiet, verbose, directory, force, noAnsi, v, notifyPolicy);
    }

    public JkConfig withNotifyPolicy(Optional<NotifyChoice> v) {
        return new JkConfig(color, offline, rebuild, noProgress, quiet, verbose, directory, force, noAnsi, noOsc, v);
    }

    /**
     * Return a new config with {@code over}'s set values laid on top of this one's. {@code over} wins
     * where it has a value; otherwise this config's value passes through.
     */
    public JkConfig mergedWith(JkConfig over) {
        return new JkConfig(
                over.color.or(() -> this.color),
                over.offline.or(() -> this.offline),
                over.rebuild.or(() -> this.rebuild),
                over.noProgress.or(() -> this.noProgress),
                over.quiet.or(() -> this.quiet),
                over.verbose.or(() -> this.verbose),
                over.directory.or(() -> this.directory),
                over.force.or(() -> this.force),
                over.noAnsi.or(() -> this.noAnsi),
                over.noOsc.or(() -> this.noOsc),
                over.notifyPolicy.or(() -> this.notifyPolicy));
    }

    /** Convenience: color with a fallback when empty. */
    public ColorChoice colorOr(ColorChoice fallback) {
        return color.orElse(fallback);
    }

    public boolean offlineOr(boolean fallback) {
        return offline.orElse(fallback);
    }

    /** True when {@code -F}/{@code --force} / {@code JK_FORCE} was set for this invocation. */
    public boolean forceOr(boolean fallback) {
        return force.orElse(fallback);
    }

    /**
     * True when this build must bypass jk's own caches ({@code force} implies it). NOT
     * {@code force.or(() -> rebuild)}: {@code Optional.or} short-circuits on PRESENCE, and wire
     * decodes materialize {@code force} as {@code Optional.of(false)} — which silently masked a
     * present-and-true {@code rebuild}.
     */
    public boolean rebuildOr(boolean fallback) {
        if (force.isEmpty() && rebuild.isEmpty()) return fallback;
        return force.orElse(false) || rebuild.orElse(false);
    }

    /** True when {@code --no-ansi} was set — all ANSI sequences suppressed, ASCII only. */
    public boolean noAnsiOr(boolean fallback) {
        return noAnsi.orElse(fallback);
    }

    /** True when {@code --no-osc} was set — no window title, taskbar progress, or notifications. */
    public boolean noOscOr(boolean fallback) {
        return noOsc.orElse(fallback);
    }

    public boolean noProgressOr(boolean fallback) {
        return noProgress.orElse(fallback);
    }

    public boolean quietOr(boolean fallback) {
        return quiet.orElse(fallback);
    }

    public boolean verboseOr(boolean fallback) {
        return verbose.orElse(fallback);
    }

    /** Notify policy with fallback (default {@link NotifyChoice#AUTO}). */
    public NotifyChoice notifyOr(NotifyChoice fallback) {
        return notifyPolicy.orElse(fallback);
    }
}
