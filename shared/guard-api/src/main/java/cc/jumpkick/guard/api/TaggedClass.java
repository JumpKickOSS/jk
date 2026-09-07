// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.ClassFacts;
import java.util.Set;

/**
 * A test class and the JUnit tags it carries — its own plus its methods', as the launcher filters
 * them.
 */
public record TaggedClass(ClassFacts cls, Set<String> tags) implements Site {

    public TaggedClass {
        tags = Set.copyOf(tags);
    }

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
