// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Mention;
import cc.jumpkick.codegraph.Relations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL read at the statement level, without a parser: the tables and columns a migration's DDL
 * defines, alters, drops and references, and the ones a query names. DDL is a definition file's
 * syntax, so its edges are declared; a query's names are mentions the linker links only to a table
 * or column some DDL defines, inferred, and a bare column several of the query's tables could hold
 * is ambiguous. What the reader cannot place contributes nothing.
 */
public final class Sql {

    private Sql() {}

    /** The most mentions one statement makes: a generated query must not flood the linker. */
    static final int MAX_MENTIONS = 200;

    private static final String NAME = "(?:\"[^\"]+\"|`[^`]+`|\\[[^\\]]+]|[A-Za-z_][\\w$]*)";
    private static final String QUALIFIED = NAME + "(?:\\s*\\.\\s*" + NAME + ")*";
    private static final Pattern CREATE_TABLE = Pattern.compile("(?i)\\bcreate\\s+(?:or\\s+replace\\s+)?"
            + "(?:(?:global\\s+|local\\s+)?(?:temp|temporary)\\s+|unlogged\\s+)?(table|view)\\s+(?:if\\s+not\\s+exists\\s+)?("
            + QUALIFIED + ")");
    private static final Pattern ALTER_TABLE =
            Pattern.compile("(?i)\\balter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?(" + QUALIFIED + ")");
    private static final Pattern DROP_TABLE =
            Pattern.compile("(?i)\\bdrop\\s+(?:table|view)\\s+(?:if\\s+exists\\s+)?(" + QUALIFIED + ")");
    private static final Pattern CREATE_INDEX = Pattern.compile("(?i)\\bcreate\\s+(?:unique\\s+)?index\\s+"
            + "(?:concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?(?:" + NAME + "\\s+)?on\\s+(?:only\\s+)?(" + QUALIFIED
            + ")\\s*(?:using\\s+\\w+\\s*)?\\(");
    private static final Pattern REFERENCES =
            Pattern.compile("(?i)\\breferences\\s+(" + QUALIFIED + ")\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern IDENT = Pattern.compile(NAME);
    private static final Pattern QUERY = Pattern.compile("(?is)^\\s*(?:select|insert|update|delete|with|merge)\\b");
    private static final Pattern WORD = Pattern.compile("(?<![\\w$.:@?])(" + NAME + ")(\\s*\\.\\s*(" + NAME + "))?");

    /** Item heads in a column list that open a constraint, not a column. */
    private static final Set<String> CONSTRAINTS = Set.of(
            "constraint",
            "primary",
            "foreign",
            "unique",
            "check",
            "exclude",
            "index",
            "key",
            "like",
            "period",
            "fulltext",
            "spatial");

    /** Words before a table name in a query. */
    private static final Set<String> TABLE_WORDS = Set.of("from", "join", "into", "update");

    /** SQL words that are never a column a query names. */
    private static final Set<String> KEYWORDS = Set.of(
            "select",
            "from",
            "where",
            "and",
            "or",
            "not",
            "in",
            "is",
            "null",
            "as",
            "on",
            "join",
            "left",
            "right",
            "inner",
            "outer",
            "full",
            "cross",
            "group",
            "by",
            "order",
            "having",
            "limit",
            "offset",
            "insert",
            "into",
            "values",
            "update",
            "set",
            "delete",
            "with",
            "distinct",
            "case",
            "when",
            "then",
            "else",
            "end",
            "asc",
            "desc",
            "like",
            "between",
            "exists",
            "union",
            "all",
            "any",
            "true",
            "false",
            "returning",
            "conflict",
            "do",
            "nothing",
            "merge",
            "using",
            "matched",
            "count",
            "sum",
            "min",
            "max",
            "avg",
            "coalesce",
            "cast",
            "now",
            "interval",
            "lower",
            "upper",
            "fetch",
            "first",
            "next",
            "rows",
            "only",
            "default",
            "over",
            "partition",
            "lateral",
            "filter",
            "ilike",
            "escape",
            "nulls",
            "last",
            "date",
            "time",
            "timestamp");

    /** True for text that reads as a SQL query or DML statement. */
    public static boolean isQuery(String text) {
        return QUERY.matcher(text).find();
    }

    /** {@code text} with SQL comments blanked and every other character kept, so offsets and lines hold. */
    static String uncommented(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                int end = text.indexOf('\'', i + 1);
                i = end < 0 ? text.length() : end + 1;
            } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                int end = text.indexOf('\n', i);
                end = end < 0 ? text.length() : end;
                for (int k = i; k < end; k++) out.setCharAt(k, ' ');
                i = end;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? text.length() : end + 2;
                for (int k = i; k < end; k++) if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
                i = end;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /** {@code text} with string literals blanked, quotes kept: a value is never a name. */
    static String unquoted(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\'') {
                int end = text.indexOf('\'', i + 1);
                end = end < 0 ? text.length() : end;
                for (int k = i + 1; k < end; k++) if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
                i = end + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /**
     * A SQL file: its DDL as declared edges from the file (definitions, drops, and the columns indexes
     * and foreign keys reference), and its other statements' names as mentions.
     */
    static void file(FragmentBuilder b, String text) {
        String sql = unquoted(uncommented(text));
        Lines lines = new Lines(text);
        int start = 0;
        for (int i = 0; i <= sql.length(); i++) {
            if (i == sql.length() || sql.charAt(i) == ';') {
                statement(b, sql, start, i, lines);
                start = i + 1;
            }
        }
    }

    private static void statement(FragmentBuilder b, String sql, int from, int to, Lines lines) {
        String s = sql.substring(from, to);
        if (s.isBlank()) return;
        Matcher m = CREATE_TABLE.matcher(s);
        if (m.lookingAt() || m.find() && s.substring(0, m.start()).isBlank()) {
            String table = name(m.group(2));
            b.anchor(
                    b.fileId(),
                    Anchors.TABLE,
                    table,
                    Relations.DEFINES,
                    Confidence.DECLARED,
                    lines.at(from + m.start(2)));
            int open = s.indexOf('(', m.end());
            if (m.group(1).equalsIgnoreCase("table")
                    && open >= 0
                    && s.substring(m.end(), open).isBlank()) {
                columns(b, table, s, open, from, lines);
            }
            references(b, s, from, lines);
            return;
        }
        Matcher a = ALTER_TABLE.matcher(s);
        if (a.find() && s.substring(0, a.start()).isBlank()) {
            alter(b, name(a.group(1)), s, a.end(), from, lines);
            references(b, s, from, lines);
            return;
        }
        Matcher d = DROP_TABLE.matcher(s);
        if (d.find() && s.substring(0, d.start()).isBlank()) {
            b.anchor(
                    b.fileId(),
                    Anchors.TABLE,
                    name(d.group(1)),
                    Relations.DROPS,
                    Confidence.DECLARED,
                    lines.at(from + d.start(1)));
            return;
        }
        Matcher x = CREATE_INDEX.matcher(s);
        if (x.find() && s.substring(0, x.start()).isBlank()) {
            String table = name(x.group(1));
            int open = x.end() - 1;
            int close = close(s, open);
            for (int[] col : identifiers(s, open + 1, close)) {
                b.anchor(
                        b.fileId(),
                        Anchors.COLUMN,
                        column(table, s.substring(col[0], col[1])),
                        Relations.USES,
                        Confidence.DECLARED,
                        lines.at(from + col[0]));
            }
            return;
        }
        if (isQuery(s)) {
            for (Use u : uses(s)) b.mention(b.fileId(), u.kind(), u.text(), lines.at(from + u.offset()));
        }
    }

    /** Each column a table's list defines, at its line; constraint items are skipped. */
    private static void columns(FragmentBuilder b, String table, String s, int open, int from, Lines lines) {
        int close = close(s, open);
        for (int[] item : items(s, open + 1, close)) {
            Matcher id = IDENT.matcher(s).region(item[0], item[1]);
            if (!id.lookingAt()) continue;
            String head = s.substring(id.start(), id.end());
            if (CONSTRAINTS.contains(head.toLowerCase(Locale.ROOT))) continue;
            b.anchor(
                    b.fileId(),
                    Anchors.COLUMN,
                    column(table, head),
                    Relations.DEFINES,
                    Confidence.DECLARED,
                    lines.at(from + id.start()));
        }
    }

    private static final Pattern ADD =
            Pattern.compile("(?i)^\\s*add\\s+(?:column\\s+)?(?:if\\s+not\\s+exists\\s+)?(" + NAME + ")");
    private static final Pattern DROP =
            Pattern.compile("(?i)^\\s*drop\\s+(?:column\\s+)?(?:if\\s+exists\\s+)?(" + NAME + ")");
    private static final Pattern RENAME_COLUMN =
            Pattern.compile("(?i)^\\s*rename\\s+(?:column\\s+)?(" + NAME + ")\\s+to\\s+(" + NAME + ")");
    private static final Pattern RENAME_TABLE = Pattern.compile("(?i)^\\s*rename\\s+to\\s+(" + QUALIFIED + ")");
    private static final Pattern CHANGE = Pattern.compile("(?i)^\\s*(?:alter|modify)\\s+(?:column\\s+)?(" + NAME
            + ")|^\\s*change\\s+(?:column\\s+)?(" + NAME + ")\\s+(" + NAME + ")");

    /** An {@code ALTER TABLE}'s actions, comma-separated: each column it adds, changes, renames or drops. */
    private static void alter(FragmentBuilder b, String table, String s, int at, int from, Lines lines) {
        for (int[] item : items(s, at, s.length())) {
            String action = s.substring(item[0], item[1]);
            int base = from + item[0];
            Matcher m;
            if ((m = RENAME_TABLE.matcher(action)).find()) {
                b.anchor(b.fileId(), Anchors.TABLE, table, Relations.DROPS, Confidence.DECLARED, lines.at(base));
                b.anchor(
                        b.fileId(),
                        Anchors.TABLE,
                        name(m.group(1)),
                        Relations.DEFINES,
                        Confidence.DECLARED,
                        lines.at(base + m.start(1)));
            } else if ((m = RENAME_COLUMN.matcher(action)).find()) {
                b.anchor(
                        b.fileId(),
                        Anchors.COLUMN,
                        column(table, m.group(1)),
                        Relations.DROPS,
                        Confidence.DECLARED,
                        lines.at(base + m.start(1)));
                b.anchor(
                        b.fileId(),
                        Anchors.COLUMN,
                        column(table, m.group(2)),
                        Relations.DEFINES,
                        Confidence.DECLARED,
                        lines.at(base + m.start(2)));
            } else if ((m = ADD.matcher(action)).find()) {
                if (CONSTRAINTS.contains(m.group(1).toLowerCase(Locale.ROOT))) continue;
                b.anchor(
                        b.fileId(),
                        Anchors.COLUMN,
                        column(table, m.group(1)),
                        Relations.DEFINES,
                        Confidence.DECLARED,
                        lines.at(base + m.start(1)));
            } else if ((m = DROP.matcher(action)).find()) {
                if (CONSTRAINTS.contains(m.group(1).toLowerCase(Locale.ROOT))) continue;
                b.anchor(
                        b.fileId(),
                        Anchors.COLUMN,
                        column(table, m.group(1)),
                        Relations.DROPS,
                        Confidence.DECLARED,
                        lines.at(base + m.start(1)));
            } else if ((m = CHANGE.matcher(action)).find()) {
                if (m.group(1) != null) {
                    b.anchor(
                            b.fileId(),
                            Anchors.COLUMN,
                            column(table, m.group(1)),
                            Relations.DEFINES,
                            Confidence.DECLARED,
                            lines.at(base + m.start(1)));
                } else {
                    b.anchor(
                            b.fileId(),
                            Anchors.COLUMN,
                            column(table, m.group(2)),
                            Relations.DROPS,
                            Confidence.DECLARED,
                            lines.at(base + m.start(2)));
                    b.anchor(
                            b.fileId(),
                            Anchors.COLUMN,
                            column(table, m.group(3)),
                            Relations.DEFINES,
                            Confidence.DECLARED,
                            lines.at(base + m.start(3)));
                }
            }
        }
    }

    /** {@code REFERENCES t (c, …)}: declared uses of the table and each column named. */
    private static void references(FragmentBuilder b, String s, int from, Lines lines) {
        Matcher r = REFERENCES.matcher(s);
        while (r.find()) {
            String table = name(r.group(1));
            b.anchor(
                    b.fileId(), Anchors.TABLE, table, Relations.USES, Confidence.DECLARED, lines.at(from + r.start(1)));
            if (r.group(2) == null) continue;
            for (int[] col : identifiers(s, r.start(2), r.end(2))) {
                b.anchor(
                        b.fileId(),
                        Anchors.COLUMN,
                        column(table, s.substring(col[0], col[1])),
                        Relations.USES,
                        Confidence.DECLARED,
                        lines.at(from + col[0]));
            }
        }
    }

    /** One name a query makes: a table, or a column with the tables it may belong to, at an offset. */
    public record Use(Mention.Kind kind, String text, int offset) {}

    /**
     * The tables and columns a query names, as mentions: a table after {@code FROM}, {@code JOIN},
     * {@code INTO} or {@code UPDATE}; a qualified column {@code o.status} on the table its alias names;
     * a bare word on every table the statement names ({@code status@orders,customers}), which the
     * linker links to the ones that have it.
     */
    public static List<Use> uses(String query) {
        String s = unquoted(uncommented(query));
        Map<String, String> aliases = new LinkedHashMap<>();
        List<Use> out = new ArrayList<>();
        Matcher w = WORD.matcher(s);
        List<int[]> words = new ArrayList<>();
        while (w.find()) words.add(new int[] {w.start(), w.end(), w.start(1), w.end(1), w.start(3), w.end(3)});
        for (int i = 0; i < words.size(); i++) {
            int[] word = words.get(i);
            String first = s.substring(word[2], word[3]);
            if (!TABLE_WORDS.contains(first.toLowerCase(Locale.ROOT)) || i + 1 >= words.size()) continue;
            for (int k = i + 1; k < words.size(); ) {
                int after = table(s, words, k, aliases, out);
                // FROM a x, b y: a comma after a table and its alias names another table.
                if (after < 0 || after >= words.size()) break;
                if (!s.substring(words.get(after - 1)[1], words.get(after)[0])
                        .strip()
                        .equals(",")) break;
                k = after;
            }
        }
        if (aliases.isEmpty()) return out;
        return columns(s, words, aliases, out);
    }

    /**
     * The table at word {@code k} and its alias, if any, recorded; returns the index of the word after
     * them, or -1 when word {@code k} is no table.
     */
    private static int table(String s, List<int[]> words, int k, Map<String, String> aliases, List<Use> out) {
        int[] word = words.get(k);
        String table = name(s.substring(word[0], word[1]));
        if (KEYWORDS.contains(table.toLowerCase(Locale.ROOT))) return -1;
        out.add(new Use(Mention.Kind.TABLE, table, word[0]));
        aliases.put(table, table);
        aliases.put(table.substring(table.lastIndexOf('.') + 1), table);
        int next = k + 1;
        if (next < words.size()
                && s.substring(words.get(next)[0], words.get(next)[1]).equalsIgnoreCase("as")) next++;
        if (next >= words.size()) return next;
        int[] alias = words.get(next);
        String a = s.substring(alias[0], alias[1]);
        String between = s.substring(word[1], alias[0]).strip();
        boolean adjacent = between.isEmpty() || between.equalsIgnoreCase("as");
        if (adjacent && alias[4] < 0 && !KEYWORDS.contains(a.toLowerCase(Locale.ROOT))) {
            aliases.put(name(a), table);
            return next + 1;
        }
        return k + 1;
    }

    /** Each column the words name, on the table its qualifier names or on every table of the statement. */
    private static List<Use> columns(String s, List<int[]> words, Map<String, String> aliases, List<Use> out) {
        Set<String> tables = new LinkedHashSet<>(aliases.values());
        for (int[] word : words) {
            if (out.size() >= MAX_MENTIONS) break;
            String head = s.substring(word[2], word[3]);
            String lower = head.toLowerCase(Locale.ROOT);
            int after = word[1];
            while (after < s.length() && Character.isWhitespace(s.charAt(after))) after++;
            if (after < s.length() && s.charAt(after) == '(') continue;
            if (word[4] >= 0) {
                String table = aliases.get(name(head));
                if (table != null) {
                    out.add(new Use(Mention.Kind.COLUMN, column(table, s.substring(word[4], word[5])), word[4]));
                }
            } else if (!KEYWORDS.contains(lower)
                    && !aliases.containsKey(name(head))
                    && !Character.isDigit(head.charAt(0))) {
                out.add(new Use(Mention.Kind.COLUMN, name(head) + "@" + String.join(",", tables), word[2]));
            }
        }
        return out;
    }

    /** The offset of the parenthesis closing the one at {@code open}; the end when none does. */
    private static int close(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return s.length();
    }

    /** The top-level comma-separated items of {@code s[from, to)} as offset pairs, blanks trimmed. */
    private static List<int[]> items(String s, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i <= to; i++) {
            char c = i < to ? s.charAt(i) : ',';
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                int a = start;
                while (a < i && Character.isWhitespace(s.charAt(a))) a++;
                if (a < i) out.add(new int[] {a, i});
                start = i + 1;
            }
        }
        return out;
    }

    /** The bare identifiers of a column list {@code s[from, to)}, one per item. */
    private static List<int[]> identifiers(String s, int from, int to) {
        List<int[]> out = new ArrayList<>();
        for (int[] item : items(s, from, to)) {
            Matcher id = IDENT.matcher(s).region(item[0], item[1]);
            if (id.lookingAt()) out.add(new int[] {id.start(), id.end()});
        }
        return out;
    }

    /** A column's name on {@code table}: the table as its anchor keys it (no default schema), the column as written. */
    private static String column(String table, String written) {
        return Anchors.key(Anchors.TABLE, table) + "." + name(written);
    }

    /** A written name with blanks around dots removed. */
    private static String name(String written) {
        return written.replaceAll("\\s*\\.\\s*", ".");
    }
}
