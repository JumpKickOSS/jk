// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
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
        Optional<Boolean> noAnsi) {

    public enum ColorChoice {
        AUTO,
        ALWAYS,
        NEVER;

        public static Optional<ColorChoice> parse(String s) {
            if (s == null || s.isBlank()) return Optional.empty();
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "auto" -> Optional.of(AUTO);
                case "always" -> Optional.of(ALWAYS);
                case "never" -> Optional.of(NEVER);
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
                Optional.empty());
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
                over.noAnsi.or(() -> this.noAnsi));
    }

    /** Convenience: color with a fallback when empty. */
    public ColorChoice colorOr(ColorChoice fallback) {
        return color.orElse(fallback);
    }

    public boolean offlineOr(boolean fallback) {
        return offline.orElse(fallback);
    }

    /** True when {@code --force} / {@code JK_FORCE} was set for this invocation. */
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

    public boolean noProgressOr(boolean fallback) {
        return noProgress.orElse(fallback);
    }

    public boolean quietOr(boolean fallback) {
        return quiet.orElse(fallback);
    }

    public boolean verboseOr(boolean fallback) {
        return verbose.orElse(fallback);
    }
}
