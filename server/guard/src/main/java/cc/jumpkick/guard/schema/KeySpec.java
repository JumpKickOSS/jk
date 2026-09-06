// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.schema;

import java.util.List;

/**
 * One key of one kind: its shape, whether it is required, the closed value set if any, and the
 * one-line doc {@code explain --schema} prints.
 *
 * @param values the closed set of accepted string values; empty means free-form
 */
public record KeySpec(String name, KeyType type, boolean required, List<String> values, String doc) {

    public KeySpec {
        values = values == null ? List.of() : List.copyOf(values);
    }

    static KeySpec required(String name, KeyType type, String doc) {
        return new KeySpec(name, type, true, List.of(), doc);
    }

    static KeySpec optional(String name, KeyType type, String doc) {
        return new KeySpec(name, type, false, List.of(), doc);
    }

    static KeySpec choice(String name, boolean required, String doc, String... values) {
        return new KeySpec(name, KeyType.STRING, required, List.of(values), doc);
    }
}
