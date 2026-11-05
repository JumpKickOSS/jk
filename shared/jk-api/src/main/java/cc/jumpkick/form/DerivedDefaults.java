// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import org.jspecify.annotations.Nullable;

/** Resolves a {@link FieldDefault.Derived} source name to a value, or {@code null} when it has none. */
@FunctionalInterface
public interface DerivedDefaults {

    /** Resolves nothing: every derived default is absent. */
    DerivedDefaults NONE = from -> null;

    @Nullable
    Object resolve(String from);
}
