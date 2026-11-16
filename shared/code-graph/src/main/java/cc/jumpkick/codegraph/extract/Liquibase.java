// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph.extract;

import cc.jumpkick.codegraph.Anchors;
import cc.jumpkick.codegraph.Confidence;
import cc.jumpkick.codegraph.FragmentBuilder;
import cc.jumpkick.codegraph.Relations;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Liquibase changelogs and MyBatis mappers, read for SQL anchors. A changelog's {@code createTable},
 * {@code addColumn}, {@code renameColumn}, {@code renameTable}, {@code dropColumn} and {@code
 * dropTable} changes define and drop tables and columns, declared, in XML and in YAML; a mapper's
 * statements name tables and columns as a query does ({@link Sql#uses}).
 */
final class Liquibase {

    private Liquibase() {}

    private static final Pattern CHANGE =
            Pattern.compile("<(createTable|addColumn|renameColumn|renameTable|dropColumn|dropTable)\\b([^>]*?)(/?)>");
    private static final Pattern COLUMN = Pattern.compile("<column\\b([^>]*?)/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern STATEMENT = Pattern.compile("(?s)<(select|insert|update|delete)\\b[^>]*>(.*?)</\\1>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern PARAMETER = Pattern.compile("[#$]\\{[^}]*}");

    /** True for an XML changelog. */
    static boolean isChangelog(String text) {
        return text.contains("<databaseChangeLog");
    }

    /** True for a MyBatis mapper. */
    static boolean isMapper(String text) {
        return text.contains("<mapper") && text.contains("namespace=");
    }

    /** An XML changelog's changes; {@code body} has its comments blanked. */
    static void xml(FragmentBuilder b, String body, Lines lines) {
        Matcher c = CHANGE.matcher(body);
        while (c.find()) {
            Map<String, String> at = attributes(c.group(2));
            int line = lines.at(c.start());
            String table = at.get("tableName");
            switch (c.group(1)) {
                case "createTable", "addColumn" -> {
                    if (table == null) continue;
                    if (c.group(1).equals("createTable")) define(b, Anchors.TABLE, table, line);
                    if (!c.group(3).isEmpty()) continue;
                    int end = body.indexOf("</" + c.group(1), c.end());
                    Matcher col = COLUMN.matcher(body).region(c.end(), end < 0 ? body.length() : end);
                    while (col.find()) {
                        String name = attributes(col.group(1)).get("name");
                        if (name != null) define(b, Anchors.COLUMN, table + "." + name, lines.at(col.start()));
                    }
                }
                case "renameColumn" -> {
                    String old = at.get("oldColumnName");
                    String now = at.get("newColumnName");
                    if (table == null || old == null || now == null) continue;
                    drop(b, Anchors.COLUMN, table + "." + old, line);
                    define(b, Anchors.COLUMN, table + "." + now, line);
                }
                case "renameTable" -> {
                    String old = at.get("oldTableName");
                    String now = at.get("newTableName");
                    if (old == null || now == null) continue;
                    drop(b, Anchors.TABLE, old, line);
                    define(b, Anchors.TABLE, now, line);
                }
                case "dropColumn" -> {
                    String column = at.get("columnName");
                    if (table != null && column != null) drop(b, Anchors.COLUMN, table + "." + column, line);
                }
                default -> {
                    if (table != null) drop(b, Anchors.TABLE, table, line);
                }
            }
        }
    }

    /** A mapper's statements, their dynamic tags and parameters blanked, as queries. */
    static void mapper(FragmentBuilder b, String body, Lines lines) {
        Matcher s = STATEMENT.matcher(body);
        while (s.find()) {
            String inner = blank(PARAMETER, blank(TAG, s.group(2)));
            int base = s.start(2);
            for (Sql.Use u : Sql.uses(inner)) b.mention(b.fileId(), u.kind(), u.text(), lines.at(base + u.offset()));
        }
    }

    /**
     * A YAML changelog, fed one key at a time with its path: the change a key sits under collects its
     * table and columns and is written when the next change starts or the file ends.
     */
    static final class Yaml {

        private final FragmentBuilder b;
        private @Nullable String change;
        private int changeDepth = -1;
        private int changeLine;
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Integer> lineOf = new HashMap<>();
        private final List<String[]> columns = new ArrayList<>();

        Yaml(FragmentBuilder b) {
            this.b = b;
        }

        /** One key at {@code path} (its own name last) with its scalar {@code value}, at {@code line}. */
        void key(List<String> path, String value, int line) {
            int depth = path.size() - 1;
            String key = path.get(depth);
            if (change != null && depth <= changeDepth) flush();
            if (CHANGES.contains(key) && path.contains("changes")) {
                change = key;
                changeDepth = depth;
                changeLine = line;
                return;
            }
            if (change == null || value.isEmpty()) return;
            String v = unquote(value);
            if (key.equals("name") && path.size() >= 2 && path.get(depth - 1).equals("column")) {
                columns.add(new String[] {v, String.valueOf(line)});
            } else {
                values.putIfAbsent(key, v);
                lineOf.putIfAbsent(key, line);
            }
        }

        /** Writes the change being read. */
        void flush() {
            String c = change;
            change = null;
            changeDepth = -1;
            if (c == null) return;
            String table = values.get("tableName");
            int line = lineOf.getOrDefault("tableName", changeLine);
            switch (c) {
                case "createTable", "addColumn" -> {
                    if (table != null) {
                        if (c.equals("createTable")) define(b, Anchors.TABLE, table, line);
                        for (String[] col : columns)
                            define(b, Anchors.COLUMN, table + "." + col[0], Integer.parseInt(col[1]));
                    }
                }
                case "renameColumn" -> {
                    String old = values.get("oldColumnName");
                    String now = values.get("newColumnName");
                    if (table != null && old != null && now != null) {
                        drop(b, Anchors.COLUMN, table + "." + old, lineOf.getOrDefault("oldColumnName", line));
                        define(b, Anchors.COLUMN, table + "." + now, lineOf.getOrDefault("newColumnName", line));
                    }
                }
                case "renameTable" -> {
                    String old = values.get("oldTableName");
                    String now = values.get("newTableName");
                    if (old != null && now != null) {
                        drop(b, Anchors.TABLE, old, lineOf.getOrDefault("oldTableName", changeLine));
                        define(b, Anchors.TABLE, now, lineOf.getOrDefault("newTableName", changeLine));
                    }
                }
                case "dropColumn" -> {
                    String column = values.get("columnName");
                    if (table != null && column != null) {
                        drop(b, Anchors.COLUMN, table + "." + column, lineOf.getOrDefault("columnName", line));
                    }
                }
                default -> {
                    if (table != null) drop(b, Anchors.TABLE, table, line);
                }
            }
            values.clear();
            lineOf.clear();
            columns.clear();
        }
    }

    private static final Set<String> CHANGES =
            Set.of("createTable", "addColumn", "renameColumn", "renameTable", "dropColumn", "dropTable");

    private static void define(FragmentBuilder b, String kind, String name, int line) {
        b.anchor(b.fileId(), kind, name, Relations.DEFINES, Confidence.DECLARED, line);
    }

    private static void drop(FragmentBuilder b, String kind, String name, int line) {
        b.anchor(b.fileId(), kind, name, Relations.DROPS, Confidence.DECLARED, line);
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> out = new HashMap<>();
        Matcher a = ATTRIBUTE.matcher(tag);
        while (a.find()) out.put(a.group(1), a.group(2));
        return out;
    }

    /** {@code text} with each match of {@code p} blanked to spaces, newlines kept, offsets held. */
    private static String blank(Pattern p, String text) {
        Matcher m = p.matcher(text);
        StringBuilder out = new StringBuilder(text);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) if (out.charAt(i) != '\n') out.setCharAt(i, ' ');
        }
        return out.toString();
    }

    private static String unquote(String s) {
        String t = s.strip();
        int hash = t.indexOf(" #");
        if (hash >= 0) t = t.substring(0, hash).strip();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
