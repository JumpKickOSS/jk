// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/** A tomlj table read as a {@link LockToml}; every accessor is tomlj's own, typed errors included. */
final class TomljLockToml implements LockToml {

    private final TomlTable table;

    TomljLockToml(TomlTable table) {
        this.table = table;
    }

    @Override
    public Set<String> keySet() {
        return table.keySet();
    }

    @Override
    public boolean contains(String key) {
        return table.contains(key);
    }

    @Override
    public boolean isTable(String key) {
        return table.isTable(key);
    }

    @Override
    public @Nullable Object get(String key) {
        return table.get(key);
    }

    @Override
    public @Nullable String getString(String key) {
        return table.getString(key);
    }

    @Override
    public @Nullable Long getLong(String key) {
        return table.getLong(key);
    }

    @Override
    public @Nullable Boolean getBoolean(String key) {
        return table.getBoolean(key);
    }

    @Override
    public @Nullable LockToml getTable(String key) {
        TomlTable nested = table.getTable(key);
        return nested == null ? null : new TomljLockToml(nested);
    }

    @Override
    public LockToml.@Nullable Array getArray(String key) {
        TomlArray array = table.getArray(key);
        return array == null ? null : new Array(array);
    }

    private static final class Array implements LockToml.Array {

        private final TomlArray array;

        Array(TomlArray array) {
            this.array = array;
        }

        @Override
        public int size() {
            return array.size();
        }

        @Override
        public String getString(int index) {
            return array.getString(index);
        }

        @Override
        public LockToml getTable(int index) {
            return new TomljLockToml(array.getTable(index));
        }
    }
}
