// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

/** Decoded hosted request. Typed fields grow per verb; the line is always available. */
public record VerbRequest(String wireType, String kind, String requestLine) {
    public VerbRequest {
        if (wireType == null || wireType.isBlank()) throw new IllegalArgumentException("wireType");
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("kind");
        if (requestLine == null || requestLine.isBlank()) throw new IllegalArgumentException("requestLine");
    }
}
