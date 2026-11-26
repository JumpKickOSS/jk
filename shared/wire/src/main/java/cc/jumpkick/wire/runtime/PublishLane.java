// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.util.Locale;
import java.util.Optional;

/**
 * A destination {@code jk publish} ships to, in the order a final run runs them: the libraries to a
 * Maven repository, the deliverables to a release, the container images to a registry.
 */
public enum PublishLane {
    LIBRARY("[publish] repository"),
    RELEASE("[publish] release"),
    IMAGE("[image] registry");

    private final String key;

    PublishLane(String key) {
        this.key = key;
    }

    /** The lane as {@code --only} spells it. */
    public String word() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The {@code jk.toml} key that gives the lane its target. */
    public String key() {
        return key;
    }

    /** The lane {@code word} names, if any. */
    public static Optional<PublishLane> parse(String word) {
        for (PublishLane lane : values()) {
            if (lane.word().equals(word)) return Optional.of(lane);
        }
        return Optional.empty();
    }
}
