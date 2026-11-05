// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One option of a {@code choice}, {@code multi} or {@code search} field. The id is the answer; it
 * holds no comma or whitespace, so a {@code multi} answer joins on commas on a command line.
 */
public record Option(
        String id, @Nullable String label, @Nullable String help) {

    private static final Pattern ID = Pattern.compile("[^,\\s]+");

    public Option {
        if (!ID.matcher(id).matches()) {
            throw new FormException("option id '" + id + "' must be non-empty, with no comma or whitespace");
        }
        Field.requireLine("option " + id + " label", label);
        Field.requireLine("option " + id + " help", help);
    }

    public static Option of(String id) {
        return new Option(id, null, null);
    }

    public static Option of(String id, String label) {
        return new Option(id, label, null);
    }

    /** The label when there is one, else the id. */
    public String display() {
        return label == null ? id : label;
    }
}
