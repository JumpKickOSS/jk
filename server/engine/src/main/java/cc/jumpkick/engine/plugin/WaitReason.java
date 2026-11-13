// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

/** Why a fork waits for its worker lease: every JVM slot is taken, or its bytes do not fit the budget. */
public enum WaitReason {
    SLOT("slot"),
    MEMORY("memory");

    private final String wire;

    WaitReason(String wire) {
        this.wire = wire;
    }

    /** The name a step's waiting state carries ({@code slot}, {@code memory}). */
    public String wire() {
        return wire;
    }
}
