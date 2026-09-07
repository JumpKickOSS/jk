// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.Descriptors;

/**
 * One invocation the facts index recorded, with the class and method it is in. The fingerprint is
 * {@code origin -> target}: it does not move when lines above it do.
 */
public record CallSite(Origin origin, cc.jumpkick.guard.facts.CallSite call) implements Site {

    /** {@code binary.Owner#name}, the invoked member without its descriptor. */
    public String target() {
        return Descriptors.binaryName(call.owner()) + "#" + call.name();
    }

    /** Whether {@code literal} is one of the string constants in this call's argument window. */
    public boolean nearbyLiteral(String literal) {
        return literal.equals(call.literalBefore()) || call.literals().contains(literal);
    }

    @Override
    public String fingerprint() {
        return origin.key() + " -> " + target() + call.desc();
    }

    @Override
    public String file() {
        return origin.sourceFile();
    }

    @Override
    public int line() {
        return call.line();
    }
}
