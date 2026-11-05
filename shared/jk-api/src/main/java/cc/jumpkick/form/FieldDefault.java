// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Where a field's default comes from: a literal in the form, or a source the engine derives the
 * value from ({@code git-email}, {@code history}, …). The model carries only the source's name; a
 * {@link DerivedDefaults} turns it into a value.
 */
public sealed interface FieldDefault {

    /**
     * A value written into the form: a {@code String}, a {@code Boolean} for {@code bool}, or a
     * {@code List<String>} for {@code multi}.
     */
    record Literal(Object value) implements FieldDefault {
        public Literal {
            if (value instanceof List<?> list) value = List.copyOf(list);
        }
    }

    /** A value the engine derives at the time the form is answered. */
    record Derived(String from) implements FieldDefault {
        private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

        public Derived {
            if (!NAME.matcher(from).matches()) {
                throw new FormException("default source '" + from + "' must match " + NAME.pattern());
            }
        }
    }
}
