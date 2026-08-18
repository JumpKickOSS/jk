// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.model.Scope;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Marker-tag styling for an engine-rendered {@code jk tree}. No {@code JkBuildParser} — the CLI
 * substitutes Theme colors client-side (JK-2151).
 */
public final class DependencyTreeStyle {

    public static final String MISSING_SUFFIX = " (missing)";

    static final char MARK_OPEN = '\u27e6';
    static final char MARK_CLOSE = '\u27e7';

    public record Styling(
            UnaryOperator<String> rail,
            UnaryOperator<String> group,
            UnaryOperator<String> artifact,
            UnaryOperator<String> version,
            UnaryOperator<String> reference,
            UnaryOperator<String> scopeBadge,
            UnaryOperator<String> boldCoord,
            UnaryOperator<String> rootLine) {

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
    }

    private DependencyTreeStyle() {}

    public static List<Scope> defaultScopeOrder() {
        return List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);
    }

    public static List<Scope> allScopeOrder() {
        return List.of(
                Scope.EXPORT,
                Scope.MAIN,
                Scope.RUNTIME,
                Scope.PROVIDED,
                Scope.PROCESSOR,
                Scope.PLATFORM,
                Scope.TEST,
                Scope.DEV,
                Scope.TEST_DEV);
    }

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
