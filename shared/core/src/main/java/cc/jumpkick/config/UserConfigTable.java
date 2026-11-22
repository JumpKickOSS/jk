// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * A table of {@code ~/.jk/config.toml} as its reader sees it. The reader declares each key it reads
 * once, as a constant it reads through ({@link #key}), and {@link UserConfigKeys} takes every known
 * key from these declarations. An {@link #open} table holds names the user chooses.
 */
public final class UserConfigTable {

    /** One key: its table's path and its bare name. */
    public record Key(List<String> table, String name) {

        /** The key as {@link TomlScan} and TOML's dotted form spell it: {@code cache.remote}. */
        public String dotted() {
            return table.isEmpty() ? name : String.join(".", table) + "." + name;
        }

        /** This key's non-blank string in the document {@code root}; empty when absent, blank or not a string. */
        public Optional<String> string(@Nullable TomlTable root) {
            if (root == null) return Optional.empty();
            return root.get(path()) instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
        }

        /** Bare keys, outermost first. */
        public List<String> path() {
            List<String> out = new ArrayList<>(table);
            out.add(name);
            return List.copyOf(out);
        }
    }

    private final List<String> path;
    private final boolean open;
    private final List<Key> keys = new ArrayList<>();

    private UserConfigTable(List<String> path, boolean open) {
        this.path = List.copyOf(path);
        this.open = open;
    }

    /** The table at {@code path}; no path is the file's top level. */
    public static UserConfigTable of(String... path) {
        return new UserConfigTable(List.of(path), false);
    }

    /** A table whose entries are names the user chooses: every key under it is known. */
    public static UserConfigTable open(String... path) {
        return new UserConfigTable(List.of(path), true);
    }

    /** Declares {@code name} as a key of this table, read through the returned key. */
    public synchronized Key key(String name) {
        Key key = new Key(path, name);
        if (open) throw new IllegalStateException(String.join(".", path) + " is open; it declares no keys");
        if (keys.contains(key)) throw new IllegalStateException(key.dotted() + " is declared twice");
        keys.add(key);
        return key;
    }

    /** This table's path, outermost first; empty for the top level. */
    public List<String> path() {
        return path;
    }

    public boolean isOpen() {
        return open;
    }

    /** The keys declared so far, in declaration order. */
    public synchronized List<Key> keys() {
        return List.copyOf(keys);
    }

    /** This table in {@code root}, or {@code null} when the document has none. */
    public @Nullable TomlTable in(@Nullable TomlTable root) {
        if (root == null) return null;
        if (path.isEmpty()) return root;
        return root.getTable(path);
    }
}
