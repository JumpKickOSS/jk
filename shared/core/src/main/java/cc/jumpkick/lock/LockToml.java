// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The table a parsed {@code jk-lock.toml} is read through — scalars, arrays of strings, arrays of
 * rows and nested tables — whichever syntax layer produced it. {@link LockRowParser} builds one
 * from the lock's own row grammar in a single pass; {@link TomljLockToml} wraps a tomlj parse of
 * anything outside that grammar. {@link LockfileReader} gives both the same meaning.
 *
 * <p>An absent key reads as {@code null}; a present key of another type is the layer's own typed
 * error — tomlj's {@code TomlInvalidTypeException}, or the row grammar's {@link
 * LockRowParser.Unrecognised}, which hands the text to tomlj for the diagnostic.
 */
interface LockToml {

    Set<String> keySet();

    boolean contains(String key);

    boolean isTable(String key);

    @Nullable
    Object get(String key);

    @Nullable
    String getString(String key);

    @Nullable
    Long getLong(String key);

    @Nullable
    Boolean getBoolean(String key);

    @Nullable
    LockToml getTable(String key);

    @Nullable
    Array getArray(String key);

    /** An array of strings or of rows; each accessor is typed the way the table's are. */
    interface Array {

        int size();

        String getString(int index);

        LockToml getTable(int index);
    }
}
