// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import org.jspecify.annotations.Nullable;

/**
 * One invoke instruction, deduplicated per {@code (origin member, target)}: the same target called
 * five times from one method is one site with {@code count = 5} and the first line. Rules match on
 * the pair; the baseline fingerprint is line-independent anyway.
 *
 * @param owner the declared owner's internal name, {@code java/security/MessageDigest}
 * @param literalBefore the string constant loaded immediately before the invoke, when there was one
 *     ({@code System.getProperty("os.name")} → {@code os.name}); the {@code args} peephole
 */
public record CallSite(
        String owner,
        String name,
        String desc,
        int line,
        @Nullable String literalBefore,
        int count) {

    /** {@code java.security.MessageDigest#getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;}. */
    public String target() {
        return Descriptors.binaryName(owner) + "#" + name + desc;
    }

    CallSite merged(int otherLine, @Nullable String otherLiteral) {
        return new CallSite(
                owner,
                name,
                desc,
                Math.min(line, otherLine),
                literalBefore != null ? literalBefore : otherLiteral,
                count + 1);
    }
}
