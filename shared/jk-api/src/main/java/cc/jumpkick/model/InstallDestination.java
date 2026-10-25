// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Where {@code jk install} puts a project's thin jar and POM: {@code [install] destination}. */
public enum InstallDestination {
    /** The Maven local repository ({@code ~/.m2/repository}), which Maven and Gradle read. */
    M2("m2"),
    /** jk's private {@code repos/jk-local} tree under the store. */
    JK("jk");

    /** The key naming a target in {@code [install]}, {@code config.toml}, the lock and the wire. */
    public static final String KEY = "destination";

    private final String spelling;

    InstallDestination(String spelling) {
        this.spelling = spelling;
    }

    /** The word {@code jk.toml}, {@code config.toml} and {@code --destination} spell this target with. */
    public String spelling() {
        return spelling;
    }

    /** The target spelled {@code word} (any case), or empty for an unknown word. */
    public static Optional<InstallDestination> parse(@Nullable String word) {
        if (word == null) return Optional.empty();
        String w = word.strip().toLowerCase(Locale.ROOT);
        for (InstallDestination t : values()) {
            if (t.spelling.equals(w)) return Optional.of(t);
        }
        return Optional.empty();
    }
}
