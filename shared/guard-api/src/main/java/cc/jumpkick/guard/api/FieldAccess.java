// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.FieldRef;

/** One field read or write the facts index recorded, with the class and method it is in. */
public record FieldAccess(Origin origin, FieldRef ref) implements Site {

    @Override
    public String fingerprint() {
        return origin.key() + " -> " + ref.target() + (ref.write() ? " =" : "");
    }

    @Override
    public String file() {
        return origin.sourceFile();
    }

    @Override
    public int line() {
        return ref.line();
    }
}
