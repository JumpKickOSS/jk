// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract.fixture;

/** An enum owner whose vocabulary lives in constructor arguments, not in constants. */
public enum Tier {
    SHA256("sha256"),
    ACTION_CACHE("action-cache");

    private final String dir;

    Tier(String dir) {
        this.dir = dir;
    }

    public String dir() {
        return dir;
    }
}
