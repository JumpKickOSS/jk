// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.ClassFacts;

/** A class as a site: the fingerprint is its binary name. */
public record ClassSite(ClassFacts cls) implements Site {

    @Override
    public String fingerprint() {
        return cls.binaryName();
    }

    @Override
    public String file() {
        return new Origin(cls, null).sourceFile();
    }

    @Override
    public int line() {
        return 0;
    }
}
