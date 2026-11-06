// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * How an answer reads as text, and back: a {@code multi} joins on commas, a {@code bool} is true or
 * false.
 */
public final class Values {

    private Values() {}

    public static String text(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }

    /**
     * A command-line answer ({@code -p key=value}, an MCP string) as the field's type: a {@code bool}
     * reads {@code true}/{@code yes}/{@code y} or {@code false}/{@code no}/{@code n}, and is
     * {@code null} for anything else; a {@code multi} splits on commas; every other kind is the
     * text itself. A value that already has a type (a {@code Boolean}, a list) is kept.
     */
    public static @Nullable Object parse(Field f, Object raw) {
        if (!(raw instanceof String text)) {
            if (raw instanceof List<?> list)
                return list.stream().map(String::valueOf).toList();
            return raw;
        }
        String s = text.strip();
        return switch (f.kind()) {
            case BOOL ->
                switch (s.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "y" -> Boolean.TRUE;
                    case "false", "no", "n" -> Boolean.FALSE;
                    default -> null;
                };
            case MULTI -> {
                List<String> ids = new ArrayList<>();
                for (String part : s.split(",")) {
                    if (!part.isBlank()) ids.add(part.strip());
                }
                yield List.copyOf(ids);
            }
            default -> text;
        };
    }
}
