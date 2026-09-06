// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.schema;

/** The TOML shape a key accepts. Checked at load; a wrong shape is a load error naming the key. */
public enum KeyType {
    STRING,
    STRING_LIST,
    /** A string or a list of strings — one owner or several. */
    STRING_OR_LIST,
    BOOL,
    INT,
    /** An integer or a float. */
    NUMBER,
    /** A number or a per-language table of numbers ({@code cap = { java = 800, kt = 600 }}). */
    NUMBER_OR_TABLE,
    TABLE,
    /** An array of inline tables ({@code allow = [{ in, reason }]}). */
    TABLE_LIST
}
