// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JUnit tags a test class carries, as read without running it: {@code classLevel} on the class
 * itself, which every test in it inherits, and {@code all}, those plus every method's. Tags a
 * superclass or a composed annotation adds are not seen; the runner's discovery is the authority.
 */
public record ClassTags(Set<String> classLevel, Set<String> all) {

    /** A {@code @Tag("…")} in source, inside a {@code @Tags} container or not. */
    private static final Pattern TAG =
            Pattern.compile("@(?:org\\.junit\\.jupiter\\.api\\.)?Tag\\(\\s*\"([^\"]+)\"\\s*\\)");

    /** The first type declaration in a source file: tags before it sit on the class. */
    private static final Pattern TYPE = Pattern.compile(
            "(?m)^\\s*(?:(?:public|protected|private|internal|final|"
                    + "abstract|sealed|non-sealed|static|open|data|strictfp)\\s+)*(?:class|interface|object|record|enum)\\s+[A-Za-z_$]");

    public ClassTags {
        classLevel = Set.copyOf(classLevel);
        all = Set.copyOf(all);
    }

    /**
     * The tags a test source declares, read from its text before it compiles: those ahead of its
     * first type declaration are the class's, every one is in {@link #all}.
     */
    public static ClassTags ofSource(String text) {
        Matcher type = TYPE.matcher(text);
        int declared = type.find() ? type.start() : text.length();
        Set<String> classLevel = new LinkedHashSet<>();
        Set<String> all = new LinkedHashSet<>();
        Matcher tag = TAG.matcher(text);
        while (tag.find()) {
            all.add(tag.group(1));
            if (tag.start() < declared) classLevel.add(tag.group(1));
        }
        return new ClassTags(classLevel, all);
    }
}
