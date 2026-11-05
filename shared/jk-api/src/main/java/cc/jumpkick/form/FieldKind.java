// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.Locale;

/**
 * What a field asks for. Each kind is allowed only because every surface can render it or escalate
 * it: the web wizard, the terminal widgets and the MCP flattening all switch over this enum.
 */
public enum FieldKind {
    /** A string, optionally matched against a pattern. */
    TEXT,
    /** {@code true} or {@code false}. */
    BOOL,
    /** One of the field's options. */
    CHOICE,
    /** A subset of the field's options, in option order. */
    MULTI,
    /** One of a large, dynamic list served by the field's source. */
    SEARCH,
    /** A directory local to the engine. */
    PATH;

    /** The wire spelling: the lower-case name. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FieldKind of(String id) {
        for (FieldKind kind : values()) {
            if (kind.id().equals(id)) return kind;
        }
        throw new FormException("unknown kind '" + id + "' (text, bool, choice, multi, search, path)");
    }
}
