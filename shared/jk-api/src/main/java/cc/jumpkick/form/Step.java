// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.List;
import java.util.regex.Pattern;

/** One page of a form: its fields, asked in order. */
public record Step(String id, String title, List<Field> fields) {

    static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");

    public Step {
        if (!ID.matcher(id).matches()) throw new FormException("step id '" + id + "' must match " + ID.pattern());
        if (title.isBlank()) throw new FormException("step " + id + ": title is empty");
        Field.requireLine("step " + id + " title", title);
        fields = List.copyOf(fields);
        if (fields.isEmpty()) throw new FormException("step " + id + " has no fields");
    }

    public static Step of(String id, String title, Field... fields) {
        return new Step(id, title, List.of(fields));
    }
}
