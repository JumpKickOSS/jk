// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.regex.Pattern;

/**
 * Line-independent identity for bytecode sites. Anonymous class ordinals and lambda counters move
 * when unrelated code above them changes, so {@code Foo$1} folds to {@code Foo$} and
 * {@code lambda$run$3} to {@code lambda$run$} — the same normalisation ArchUnit's freeze store
 * applies.
 */
public final class Fingerprints {

    private static final Pattern ANON = Pattern.compile("\\$\\d+");
    private static final Pattern LAMBDA = Pattern.compile("(lambda\\$[^$(]+\\$)\\d+");

    private Fingerprints() {}

    public static String normalise(String originMember) {
        String s = ANON.matcher(originMember).replaceAll("\\$");
        return LAMBDA.matcher(s).replaceAll("$1");
    }
}
