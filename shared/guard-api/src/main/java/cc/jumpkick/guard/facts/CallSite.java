// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One invoke instruction, deduplicated per {@code (origin member, target)}: the same target called
 * five times from one method is one site with {@code count = 5} and the first line. Rules match on
 * the pair; the baseline fingerprint is line-independent anyway.
 *
 * @param owner the declared owner's internal name, {@code java/security/MessageDigest}
 * @param literalBefore the string constant loaded immediately before the first such invoke, when
 *     there was one ({@code System.getProperty("os.name")} → {@code os.name}); the {@code args} peephole
 * @param literals every distinct string constant loaded since the previous invoke, field access or
 *     allocation, across the merged invokes: the argument window. {@code getProperty("os.name", "")}
 *     carries both, so an {@code args} peephole sees the key behind a default; an enum's constructor
 *     calls in {@code <clinit>} carry the enum's vocabulary here
 */
public record CallSite(
        String owner,
        String name,
        String desc,
        int line,
        @Nullable String literalBefore,
        int count,
        List<String> literals) {

    public CallSite {
        literals = List.copyOf(literals);
    }

    /** A first sighting: one literal (or none), count one. */
    public CallSite(String owner, String name, String desc, int line, @Nullable String literalBefore, int count) {
        this(owner, name, desc, line, literalBefore, count, literalBefore == null ? List.of() : List.of(literalBefore));
    }

    /** A first sighting with its whole argument window. */
    public static CallSite first(
            String owner, String name, String desc, int line, @Nullable String literalBefore, List<String> window) {
        return new CallSite(owner, name, desc, line, literalBefore, 1, distinct(List.of(), window));
    }

    /** This site seen again from the same member, with that invoke's argument window. */
    public CallSite merged(int otherLine, @Nullable String otherLiteral, List<String> window) {
        CallSite m = merged(otherLine, otherLiteral);
        return new CallSite(m.owner, m.name, m.desc, m.line, m.literalBefore, m.count, distinct(m.literals, window));
    }

    private static List<String> distinct(List<String> base, List<String> more) {
        List<String> all = new ArrayList<>(base);
        for (String s : more) if (!all.contains(s)) all.add(s);
        return all;
    }

    /** {@code java.security.MessageDigest#getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;}. */
    public String target() {
        return Descriptors.binaryName(owner) + "#" + name + desc;
    }

    /** This site seen again from the same member: first line, first literal, every distinct literal. */
    public CallSite merged(int otherLine, @Nullable String otherLiteral) {
        int first = line == 0 ? otherLine : otherLine == 0 ? line : Math.min(line, otherLine);
        List<String> all = literals;
        if (otherLiteral != null && !literals.contains(otherLiteral)) {
            all = new ArrayList<>(literals);
            all.add(otherLiteral);
        }
        return new CallSite(
                owner, name, desc, first, literalBefore != null ? literalBefore : otherLiteral, count + 1, all);
    }
}
