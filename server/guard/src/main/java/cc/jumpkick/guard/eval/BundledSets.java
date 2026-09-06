// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code @name} signature sets shipped with jk, one resource file per set, one signature per
 * line, {@code #} comments. A set is expanded into a rule's signatures at evaluation; a set name
 * nobody ships is a rule error naming the known sets.
 */
public final class BundledSets {

    public static final Set<String> NAMES = Set.of(
            "jdk-system-out",
            "jdk-unsafe",
            "jdk-deprecated",
            "jdk-reflection",
            "jakarta-not-javax",
            "junit4",
            "android-log");

    private BundledSets() {}

    public static boolean isSetReference(String signature) {
        return signature.startsWith("@");
    }

    /** The signatures of {@code @name}; empty when the set is unknown. */
    public static Optional<List<String>> expand(String reference) {
        String name = reference.substring(1);
        if (!NAMES.contains(name)) return Optional.empty();
        String res = "/cc/jumpkick/guard/sets/" + name + ".txt";
        try (InputStream in = BundledSets.class.getResourceAsStream(res)) {
            if (in == null) return Optional.empty();
            List<String> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                out.add(s);
            }
            return Optional.of(out);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
