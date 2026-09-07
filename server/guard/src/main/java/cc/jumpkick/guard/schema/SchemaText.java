// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.schema;

import java.util.List;
import java.util.Locale;

/**
 * The schema rendered for humans and agents: {@code jk guard explain --schema <kind>} and the
 * manual's kind reference both come from here, so neither can lag the loader.
 */
public final class SchemaText {

    private SchemaText() {}

    /** Keys every kind accepts, in print order. */
    public static List<KeySpec> commonKeys() {
        return KindSchemas.COMMON;
    }

    /** The {@code --schema <kind>} card: keys with shape and doc, then one example. */
    public static String render(Kind kind) {
        StringBuilder sb = new StringBuilder();
        sb.append("kind = \"")
                .append(kind.id())
                .append("\"  — ")
                .append(kind.summary())
                .append('\n');
        sb.append("substrate: ").append(kind.substrate().name().toLowerCase(Locale.ROOT));
        sb.append(" · lane: ")
                .append(kind.lane().name().toLowerCase(Locale.ROOT))
                .append("\n\n");
        sb.append("keys:\n");
        for (KeySpec k : kind.keys()) line(sb, k);
        switch (kind.instead()) {
            case REQUIRED -> sb.append("  instead (string, required): the sanctioned alternative an agent applies\n");
            case OPTIONAL -> sb.append("  instead (string): the sanctioned alternative; derived when absent\n");
            case ABSENT -> {}
        }
        for (Kind.KeyGroup g : kind.groups()) {
            sb.append("  ")
                    .append(g.exactlyOne() ? "exactly one of: " : "at least one of: ")
                    .append(String.join(", ", g.keys()))
                    .append('\n');
        }
        if (kind == Kind.PARITY || kind == Kind.GENERATED) {
            sb.append("extractors (one per left/right/source, `{ name = arg }` or `{ name = { arg = … } }`):\n");
            for (var e : ExtractorVocabulary.EXTRACTORS.entrySet())
                sb.append("  ")
                        .append(e.getKey())
                        .append(' ')
                        .append(e.getValue())
                        .append('\n');
        }
        if (kind == Kind.GENERATED) {
            sb.append("templates:\n");
            for (var e : ExtractorVocabulary.TEMPLATES.entrySet())
                sb.append("  ")
                        .append(e.getKey())
                        .append(' ')
                        .append(e.getValue())
                        .append('\n');
        }
        sb.append("common keys:\n");
        for (KeySpec k : KindSchemas.COMMON) {
            if (k.name().equals("kind")) continue;
            line(sb, k);
        }
        sb.append("\nexample:\n").append(kind.example());
        return sb.toString();
    }

    private static void line(StringBuilder sb, KeySpec k) {
        sb.append("  ").append(k.name()).append(" (").append(shape(k));
        if (k.required()) sb.append(", required");
        sb.append("): ").append(k.doc());
        if (!k.values().isEmpty() && !k.name().equals("kind"))
            sb.append(" [").append(String.join(" | ", k.values())).append(']');
        sb.append('\n');
    }

    private static String shape(KeySpec k) {
        return switch (k.type()) {
            case STRING -> "string";
            case STRING_LIST -> "list";
            case STRING_OR_LIST -> "string or list";
            case BOOL -> "bool";
            case INT -> "int";
            case NUMBER -> "number";
            case NUMBER_OR_TABLE -> "number or per-language table";
            case TABLE -> "table";
            case TABLE_LIST -> "list of tables";
        };
    }
}
