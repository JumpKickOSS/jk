// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;

/**
 * The {@code [application] lib-dir} distribution: {@code target/<name>.jar}, a copy of the thin
 * jar whose manifest {@code Class-Path} names every runtime jar under {@code dir}, and {@code
 * target/<dir>/} holding those jars, so {@code java -jar target/<name>.jar} runs.
 *
 * @param dir the lib directory, relative to {@code target/} and to the jar; {@value #DEFAULT_DIR}
 *     by default
 * @param versions whether a jar's file name carries its version ({@code artifact-1.2.jar}) or
 *     not ({@code artifact.jar})
 * @param exclude runtime dependencies left out of the directory and the {@code Class-Path}: an
 *     {@code artifact} name (any group), {@code group:artifact}, or {@code group:*}
 */
public record LibDir(String dir, boolean versions, List<String> exclude) {

    public static final String DEFAULT_DIR = "libs";

    public LibDir {
        dir = dir == null || dir.isBlank() ? DEFAULT_DIR : dir;
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    /** The default layout: {@code libs/}, versioned names, nothing excluded. */
    public static LibDir defaults() {
        return new LibDir(DEFAULT_DIR, true, List.of());
    }

    /** Whether {@link #exclude} names {@code group:artifact}. */
    public boolean excludes(String group, String artifact) {
        for (String rule : exclude) {
            int colon = rule.indexOf(':');
            if (colon < 0) {
                if (rule.equals(artifact)) return true;
                continue;
            }
            String g = rule.substring(0, colon);
            String a = rule.substring(colon + 1);
            if (g.equals(group) && (a.equals("*") || a.equals(artifact))) return true;
        }
        return false;
    }
}
