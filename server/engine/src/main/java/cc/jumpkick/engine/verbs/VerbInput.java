// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

/** Raw wire line before a verb decodes it. */
public record VerbInput(String requestLine) {
    public VerbInput {
        if (requestLine == null || requestLine.isBlank()) throw new IllegalArgumentException("requestLine");
    }
}
