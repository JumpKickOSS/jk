// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Scope;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The presentation vocabulary of {@code jk tree}: the {@link Styling} operators, the marker-tag
 * wire form and its inverse, the scope display order, and the scope badge.
 *
 * <p>Nothing here parses or reads anything — no {@code JkBuildParser}, no {@code Lockfile} — which
 * is why the CLI can reach it (: substituting Theme colors client-side must not drag tomlj
 * onto the native image). {@link DependencyTree} depends on this class, never the reverse; that
 * direction is what keeps the CLI off the parser.
 */
public final class DependencyTreeStyle {

    /**
     * Suffix appended to a node whose module has no resolved {@code Lockfile.Artifact} — it is
     * present in the graph but absent from the lockfile/local cache. Callers can scan rendered
     * output for this marker to decide whether to surface a hint.
     */
    public static final String MISSING_SUFFIX = " (missing)";

    /** Marker-tag delimiters for {@link Styling#markers()} — printable, so they survive Jsonl. */
    static final char MARK_OPEN = '\u27e6'; // ⟦

    static final char MARK_CLOSE = '\u27e7'; // ⟧

    /**
     * Optional ANSI styling for tree output. Each operator wraps the matching piece of text with
     * whatever escape sequence the caller prefers. Defaults to {@link #plain()} (identity
     * everywhere) so tests and non-color consumers get raw ASCII back.
     *
     * <p>The fields map directly to the rendered shape:
     *
     * <pre>
     *   {rail}└─ {/rail}{group}{group}{/group}:{artifact}{artifact}{/artifact}:{version}{version}{/version}
     * </pre>
     *
     * <p>{@code reference} styles a whole already-shown row (the {@code ⎋} back-reference lines):
     * the connector, coordinate, and marker are dimmed as one unit so the reader sees at a glance
     * it's a pointer to an earlier expansion, not a fresh node. {@code scopeBadge} styles a scope
     * section header (the {@code main} / {@code test} / … badges grouping a project's direct
     * dependencies).
     */
    public record Styling(
            UnaryOperator<String> rail,
            UnaryOperator<String> group,
            UnaryOperator<String> artifact,
            UnaryOperator<String> version,
            UnaryOperator<String> reference,
            UnaryOperator<String> scopeBadge,
            UnaryOperator<String> boldCoord,
            UnaryOperator<String> rootLine) {

        /** No {@code rootLine} override: the root line renders as {@code " ● coord"}. */
        public Styling(
                UnaryOperator<String> rail,
                UnaryOperator<String> group,
                UnaryOperator<String> artifact,
                UnaryOperator<String> version,
                UnaryOperator<String> reference,
                UnaryOperator<String> scopeBadge,
                UnaryOperator<String> boldCoord) {
            this(
                    rail,
                    group,
                    artifact,
                    version,
                    reference,
                    scopeBadge,
                    boldCoord,
                    gav -> " " + rail.apply("●") + " " + boldCoord.apply(gav));
        }

        /**
         * The wire form (thin client): each styled piece wraps in {@code ⟦<kind>…⟧} marker tags
         * instead of ANSI escapes, so the engine can run the full composite-aware render — which
         * needs the parsed models — while the client, which owns the Theme, substitutes the tags
         * with its real stylers afterwards ({@link #applyStyling}). Tags never nest: every styler
         * in this class receives raw text.
         */
        public static Styling markers() {
            return new Styling(tag('r'), tag('g'), tag('a'), tag('v'), tag('f'), tag('b'), tag('c'));
        }

        private static UnaryOperator<String> tag(char kind) {
            return s -> String.valueOf(MARK_OPEN) + kind + s + MARK_CLOSE;
        }

        /** Identity everywhere — raw ASCII out. */
        public static Styling plain() {
            UnaryOperator<String> id = UnaryOperator.identity();
            return new Styling(id, id, id, id, id, id, id);
        }
    }

    private DependencyTreeStyle() {}

    /**
     * Default scopes for {@code jk tree} (and empty {@code --scopes}): production runtime classpath
     * — {@code export}, {@code main}, {@code runtime}. Matches {@code ClasspathResolver.RUNTIME} /
     * the {@code exec}/{@code run} meta-scopes. Use {@link #allScopeOrder()} for every scope.
     */
    public static List<Scope> defaultScopeOrder() {
        return List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);
    }

    /**
     * Every scope in display order — the {@code --scopes all} expansion, and the section order a
     * render walks when the caller gives none.
     */
    public static List<Scope> allScopeOrder() {
        return List.of(
                Scope.EXPORT,
                Scope.MAIN,
                Scope.RUNTIME,
                Scope.PROVIDED,
                Scope.PROCESSOR,
                Scope.PLATFORM,
                Scope.MANAGED,
                Scope.TEST,
                Scope.DEV,
                Scope.TEST_DEV);
    }

    /** The scope sections to consider, in display order: an explicit override or the default set. */
    static List<Scope> sectionOrder(@Nullable List<Scope> override) {
        return override != null ? override : defaultScopeOrder();
    }

    /**
     * The bare lowercase scope name for a section badge: {@code MAIN} → {@code "main"}. The {@link
     * Styling#scopeBadge} styler decides padding vs. pill caps.
     */
    static String scopeLabel(Scope s) {
        return s.name().toLowerCase(Locale.ROOT);
    }

    /** All scope badges joined on one line — the {@code --stack} header. */
    static String badgeRow(List<Scope> scopes, Styling styling) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scopes.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(styling.scopeBadge().apply(scopeLabel(scopes.get(i))));
        }
        return sb.toString();
    }

    /**
     * Substitute {@link Styling#markers()} tags in an engine-rendered tree with this client's real
     * stylers. Unknown kinds render unstyled; an unterminated tag renders literally (defensive —
     * the engine only ever emits balanced tags).
     */
    public static String applyStyling(String rendered, Styling styling) {
        StringBuilder out = new StringBuilder(rendered.length());
        int i = 0;
        while (i < rendered.length()) {
            char c = rendered.charAt(i);
            if (c != MARK_OPEN || i + 1 >= rendered.length()) {
                out.append(c);
                i++;
                continue;
            }
            int close = rendered.indexOf(MARK_CLOSE, i + 1);
            if (close < 0) {
                out.append(c);
                i++;
                continue;
            }
            char kind = rendered.charAt(i + 1);
            String content = rendered.substring(i + 2, close);
            UnaryOperator<String> styler =
                    switch (kind) {
                        case 'r' -> styling.rail();
                        case 'g' -> styling.group();
                        case 'a' -> styling.artifact();
                        case 'v' -> styling.version();
                        case 'f' -> styling.reference();
                        case 'b' -> styling.scopeBadge();
                        case 'c' -> styling.boldCoord();
                        default -> UnaryOperator.identity();
                    };
            out.append(styler.apply(content));
            i = close + 1;
        }
        return out.toString();
    }
}
