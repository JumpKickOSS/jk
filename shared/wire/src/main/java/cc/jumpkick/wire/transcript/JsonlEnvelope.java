// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.transcript;

import cc.jumpkick.jsonl.JsonFields;

/** The prefix every CLI JSONL line shares: {@code schema}, {@code ts} (epoch ms) and {@code type}. */
public final class JsonlEnvelope {
    /** Stays on {@code 1} until jk 1.0 — additive fields only. */
    public static final int SCHEMA = 1;

    private JsonlEnvelope() {}

    /** An object opened with the envelope fields; the caller adds the line's own and finishes it. */
    public static JsonFields open(long ts, String type) {
        return JsonFields.object().number("schema", SCHEMA).number("ts", ts).string("type", type);
    }
}
