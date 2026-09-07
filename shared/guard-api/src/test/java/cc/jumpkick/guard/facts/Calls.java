// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import org.jspecify.annotations.Nullable;

/** Builds a facts-level call site for tests in packages where the API's {@code CallSite} is the imported one. */
public final class Calls {
    private Calls() {}

    public static CallSite call(String owner, String name, String desc, int line, @Nullable String literal, int count) {
        return new CallSite(owner, name, desc, line, literal, count);
    }
}
