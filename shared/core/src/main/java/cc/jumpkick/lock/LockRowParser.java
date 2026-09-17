// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads the row grammar {@link LockfileWriter} emits — bare keys, basic strings, integers,
 * booleans, arrays of basic strings, {@code [table]} and {@code [[row]]} headers — straight into
 * {@link LockToml} tables, in one pass over the text with one object per value. tomlj's parse
 * tree holds several objects per quoted character, which on a megabyte lock is hundreds of
 * megabytes of heap before a single row exists; the row grammar's tables are the size of the lock.
 *
 * <p>Anything outside the grammar — a comment, a literal or multi-line string, an inline table, a
 * dotted or repeated header, a duplicate key, a float, a value read as the wrong type — is {@link
 * Unrecognised}, and the reader hands the text to tomlj, whose full grammar and diagnostics stand.
 * The parser accepts nothing tomlj would refuse, so a lock that reads here reads the same there.
 */
final class LockRowParser {

    /** Text the row grammar does not cover; the caller parses it with tomlj instead. */
    static final class Unrecognised extends RuntimeException {
        Unrecognised(String detail) {
            super(detail, null, false, false);
        }
    }

    private final String text;
    private final int length;
    private int pos;
    private final MapTable root = new MapTable();
    private MapTable current = root;

    private LockRowParser(String text) {
        this.text = text;
        this.length = text.length();
    }

    /** The document {@code text} describes, or {@link Unrecognised} when any of it is outside the grammar. */
    static LockToml parse(String text) {
        LockRowParser parser = new LockRowParser(text);
        parser.document();
        return parser.root;
    }

    private void document() {
        while (true) {
            skipBlanks();
            if (pos >= length) return;
            char c = text.charAt(pos);
            if (c == '\n') {
                pos++;
                continue;
            }
            if (c == '\r' && text.startsWith("\n", pos + 1)) {
                pos += 2;
                continue;
            }
            if (c == '[') header();
            else keyValue();
            endOfLine();
        }
    }

    /** {@code [name]} opens a table defined once; {@code [[name]]} appends a row to an array of rows. */
    private void header() {
        pos++;
        boolean row = peek() == '[';
        if (row) pos++;
        skipBlanks();
        String name = bareKey();
        skipBlanks();
        expect(']');
        if (row) expect(']');
        Object existing = root.values.get(name);
        if (row) {
            MapArray rows;
            if (existing == null) {
                rows = new MapArray(true);
                root.values.put(name, rows);
            } else if (existing instanceof MapArray array && array.rows) {
                rows = array;
            } else {
                throw new Unrecognised("[[" + name + "]] beside a " + name + " that is not an array of rows");
            }
            MapTable table = new MapTable();
            rows.items.add(table);
            current = table;
        } else {
            if (existing != null) throw new Unrecognised("[" + name + "] beside an existing " + name);
            MapTable table = new MapTable();
            root.values.put(name, table);
            current = table;
        }
    }

    /** {@code key = value}, with a dotted key descending into tables the key path creates. */
    private void keyValue() {
        List<String> path = dottedKey();
        skipBlanks();
        expect('=');
        skipBlanks();
        Object value = value();
        MapTable target = current;
        for (int i = 0; i < path.size() - 1; i++) {
            String segment = path.get(i);
            Object next = target.values.get(segment);
            if (next == null) {
                MapTable created = new MapTable();
                target.values.put(segment, created);
                next = created;
            } else if (!(next instanceof MapTable)) {
                throw new Unrecognised("dotted key through a value: " + segment);
            }
            target = (MapTable) next;
        }
        if (target.values.putIfAbsent(path.getLast(), value) != null) {
            throw new Unrecognised("duplicate key " + path.getLast());
        }
    }

    private List<String> dottedKey() {
        List<String> path = new ArrayList<>(1);
        path.add(bareKey());
        while (true) {
            skipBlanks();
            if (peek() != '.') return path;
            pos++;
            skipBlanks();
            path.add(bareKey());
        }
    }

    private String bareKey() {
        int start = pos;
        while (pos < length && isBareKeyChar(text.charAt(pos))) pos++;
        if (pos == start) throw new Unrecognised("bare key expected at " + pos);
        return text.substring(start, pos);
    }

    private static boolean isBareKeyChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    private Object value() {
        char c = peek();
        if (c == '"') return basicString();
        if (c == '[') return array();
        if (c == '-' || (c >= '0' && c <= '9')) return integer();
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new Unrecognised("value outside the row grammar at " + pos);
    }

    /** A decimal integer with no sign but minus, no separators and no leading zero. */
    private Long integer() {
        int start = pos;
        if (peek() == '-') pos++;
        int digits = pos;
        while (pos < length && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') pos++;
        if (pos == digits) throw new Unrecognised("digits expected at " + digits);
        if (text.charAt(digits) == '0' && pos - digits > 1) throw new Unrecognised("leading zero at " + digits);
        try {
            return Long.parseLong(text, start, pos, 10);
        } catch (NumberFormatException outOfRange) {
            throw new Unrecognised("integer out of range at " + start);
        }
    }

    /** A one-line basic string: TOML's escapes, no control characters, no line breaks. */
    private String basicString() {
        pos++;
        if (text.startsWith("\"\"", pos)) throw new Unrecognised("multi-line string at " + pos);
        int start = pos;
        StringBuilder decoded = null;
        while (true) {
            if (pos >= length) throw new Unrecognised("unterminated string from " + start);
            char c = text.charAt(pos);
            if (c == '"') {
                String value = decoded == null ? text.substring(start, pos) : decoded.toString();
                pos++;
                return value;
            }
            if (c == '\\') {
                if (decoded == null) decoded = new StringBuilder(pos - start + 16).append(text, start, pos);
                pos++;
                escape(decoded);
                continue;
            }
            if ((c < 0x20 && c != '\t') || c == 0x7F) throw new Unrecognised("control character at " + pos);
            if (decoded != null) decoded.append(c);
            pos++;
        }
    }

    /** The character after a backslash, appended decoded; TOML's set and no other. */
    private void escape(StringBuilder into) {
        if (pos >= length) throw new Unrecognised("unterminated escape");
        char e = text.charAt(pos++);
        switch (e) {
            case 'b' -> into.append('\b');
            case 't' -> into.append('\t');
            case 'n' -> into.append('\n');
            case 'f' -> into.append('\f');
            case 'r' -> into.append('\r');
            case '"' -> into.append('"');
            case '\\' -> into.append('\\');
            case 'u' -> into.appendCodePoint(codePoint(4));
            case 'U' -> into.appendCodePoint(codePoint(8));
            default -> throw new Unrecognised("escape \\" + e + " at " + (pos - 1));
        }
    }

    /** {@code digits} hex digits naming a scalar code point. */
    private int codePoint(int digits) {
        if (pos + digits > length) throw new Unrecognised("short unicode escape at " + pos);
        int value = 0;
        for (int i = 0; i < digits; i++) {
            int hex = Character.digit(text.charAt(pos + i), 16);
            if (hex < 0) throw new Unrecognised("unicode escape at " + pos);
            value = (value << 4) | hex;
        }
        if (!Character.isValidCodePoint(value) || (value >= 0xD800 && value <= 0xDFFF)) {
            throw new Unrecognised("unicode escape names no scalar value at " + pos);
        }
        pos += digits;
        return value;
    }

    /** {@code [ "a", "b", ]} on one line or many; every element a basic string. */
    private MapArray array() {
        pos++;
        MapArray out = new MapArray(false);
        while (true) {
            skipBlanksAndLineBreaks();
            if (peek() == ']') {
                pos++;
                return out;
            }
            if (peek() != '"') throw new Unrecognised("array element outside the row grammar at " + pos);
            out.items.add(basicString());
            skipBlanksAndLineBreaks();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return out;
            }
            throw new Unrecognised("array separator expected at " + pos);
        }
    }

    /** Only blanks may follow a statement on its line; a comment is outside the grammar. */
    private void endOfLine() {
        skipBlanks();
        if (pos >= length) return;
        char c = text.charAt(pos);
        if (c == '\n') {
            pos++;
        } else if (c == '\r' && text.startsWith("\n", pos + 1)) {
            pos += 2;
        } else {
            throw new Unrecognised("text after a statement at " + pos);
        }
    }

    private void skipBlanks() {
        while (pos < length && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) pos++;
    }

    private void skipBlanksAndLineBreaks() {
        while (pos < length) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n') pos++;
            else if (c == '\r' && text.startsWith("\n", pos + 1)) pos += 2;
            else return;
        }
    }

    private void expect(char c) {
        if (peek() != c) throw new Unrecognised("'" + c + "' expected at " + pos);
        pos++;
    }

    /** The character at the cursor, or NUL at the end of the text (which no grammar rule accepts). */
    private char peek() {
        return pos < length ? text.charAt(pos) : '\0';
    }

    /** A table: one map, values typed by the grammar (String, Long, Boolean, MapArray, MapTable). */
    private static final class MapTable implements LockToml {

        final Map<String, Object> values = new HashMap<>();

        @Override
        public Set<String> keySet() {
            return values.keySet();
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public boolean isTable(String key) {
            return values.get(key) instanceof MapTable;
        }

        @Override
        public @Nullable Object get(String key) {
            return values.get(key);
        }

        @Override
        public @Nullable String getString(String key) {
            return typed(key, String.class);
        }

        @Override
        public @Nullable Long getLong(String key) {
            return typed(key, Long.class);
        }

        @Override
        public @Nullable Boolean getBoolean(String key) {
            return typed(key, Boolean.class);
        }

        @Override
        public @Nullable LockToml getTable(String key) {
            return typed(key, MapTable.class);
        }

        @Override
        public LockToml.@Nullable Array getArray(String key) {
            return typed(key, MapArray.class);
        }

        private <T> @Nullable T typed(String key, Class<T> type) {
            Object value = values.get(key);
            if (value == null) return null;
            if (type.isInstance(value)) return type.cast(value);
            throw new Unrecognised(key + " is not a " + type.getSimpleName());
        }
    }

    /** An array of strings, or — opened by {@code [[name]]} headers — of rows. */
    private static final class MapArray implements LockToml.Array {

        final List<Object> items = new ArrayList<>();
        final boolean rows;

        MapArray(boolean rows) {
            this.rows = rows;
        }

        @Override
        public int size() {
            return items.size();
        }

        @Override
        public String getString(int index) {
            if (items.get(index) instanceof String s) return s;
            throw new Unrecognised("element " + index + " is not a string");
        }

        @Override
        public LockToml getTable(int index) {
            if (items.get(index) instanceof MapTable t) return t;
            throw new Unrecognised("element " + index + " is not a row");
        }
    }
}
