// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;

/**
 * The owner probe: a rule that bans a thing everywhere but one place must see that place, or it is
 * looking at nothing.
 */
public final class Owner {

    private Owner() {}

    /** The owner class, or {@link OwnerMissing} when the facts in scope do not contain it. */
    public static ClassFacts require(Facts facts, String binaryName) {
        String internal = Descriptors.internalName(binaryName);
        for (ClassFacts c : facts.classes()) if (c.name().equals(internal)) return c;
        throw new OwnerMissing(
                "owner " + binaryName + " is not in the facts in scope: the rule would pass over nothing");
    }
}
