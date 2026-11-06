// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One field as one compact line, for a reader that types the answers itself: MCP's
 * {@code action=form}, the {@code jk new --help} listing and the error that names what is missing.
 *
 * <pre>
 * database: none|postgres|mysql = postgres  # Database
 * deps: [guava,jackson,picocli] = []  # Libraries
 * native: true|false = false (advanced)  # Native binary
 * name: text /[a-z]+/ (required)  # Project name
 * migrations: flyway|liquibase = flyway when database != 'none'  # Migrations
 * </pre>
 */
public final class FieldLine {

    private FieldLine() {}

    public static String of(Field f) {
        StringBuilder out = new StringBuilder(f.key()).append(": ").append(values(f));
        switch (f.defaultValue()) {
            case null -> {
                if (f.required()) out.append(" (required)");
            }
            case FieldDefault.Literal literal -> out.append(" = ").append(literal(f, literal.value()));
            case FieldDefault.Derived derived -> out.append(" = from ").append(derived.from());
        }
        Condition when = f.when();
        if (when != null) out.append(" when ").append(when.source());
        if (f.advanced()) out.append(" (advanced)");
        return out.append("  # ").append(f.label()).toString();
    }

    /** What the field takes: its options, {@code true|false}, or its kind and pattern. */
    public static String values(Field f) {
        return switch (f.kind()) {
            case CHOICE -> ids(f, "|");
            case MULTI -> "[" + ids(f, ",") + "]";
            case BOOL -> "true|false";
            case TEXT -> f.pattern() == null ? "text" : "text /" + f.pattern() + "/";
            case SEARCH, PATH -> f.kind().id();
        };
    }

    /** The command-line answer the field takes: {@code -p database=none|postgres|mysql}. */
    public static String param(Field f) {
        String takes =
                switch (f.kind()) {
                    case CHOICE -> ids(f, "|");
                    case MULTI -> ids(f, ",");
                    case BOOL -> "true|false";
                    case TEXT, SEARCH, PATH -> "…";
                };
        return "-p " + f.key() + "=" + takes;
    }

    private static String ids(Field f, String separator) {
        return f.options().stream().map(Option::id).collect(Collectors.joining(separator));
    }

    private static String literal(Field f, Object value) {
        if (f.kind() == FieldKind.MULTI && value instanceof List<?>) return "[" + Values.text(value) + "]";
        return Values.text(value);
    }
}
