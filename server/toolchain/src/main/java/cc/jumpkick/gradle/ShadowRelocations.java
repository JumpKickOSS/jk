// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static cc.jumpkick.gradle.GradleScriptText.STR;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.RelocationRules;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Shadow's {@code relocate} calls in a build script — {@code relocate("from", "to")}, the Groovy
 * {@code relocate 'from', 'to'}, either with a trailing block of {@code include} / {@code exclude}
 * patterns — as the {@code relocate} rules they agree with ({@link RelocationRules}); what the
 * rules do not express is a row.
 */
final class ShadowRelocations {

    /** {@code relocate(STR, STR)} with or without parentheses; the block, when one follows, starts at the brace. */
    private static final Pattern RELOCATE =
            Pattern.compile("\\brelocate\\s*\\(?\\s*" + STR + "\\s*,\\s*" + STR + "\\s*\\)?\\s*(\\{)?");

    /** {@code include(STR)} / {@code exclude STR} inside a relocate block. */
    private static final Pattern NARROWING = Pattern.compile("\\b(include|exclude)\\s*\\(?\\s*" + STR);

    private ShadowRelocations() {}

    /** The rules the script's relocations agree with, the rest reported as one row. */
    static RelocationRules.Mapped map(String script, ImportReport.Builder report) {
        List<RelocationRules.Relocation> relocations = parse(script);
        RelocationRules.Mapped mapped = RelocationRules.map(relocations);
        if (!mapped.unmapped().isEmpty()) {
            report.warning("Shadow `relocate` " + String.join(", ", mapped.unmapped())
                    + " — `relocate` moves whole packages, first rule winning; an exclude no other relocation"
                    + " lands where the rule would, and an include naming a class or a package's direct classes"
                    + " the rules move elsewhere, are not written, and those classes are bundled under their own"
                    + " names.");
        }
        return mapped;
    }

    /** Every {@code relocate} call in {@code script}, in order, with its block's includes and excludes. */
    static List<RelocationRules.Relocation> parse(String script) {
        List<RelocationRules.Relocation> out = new ArrayList<>();
        Matcher m = RELOCATE.matcher(script);
        while (m.find()) {
            String from = first(m.group(1), m.group(2));
            String to = first(m.group(3), m.group(4));
            if (from == null || to == null) continue;
            List<String> includes = new ArrayList<>();
            List<String> excludes = new ArrayList<>();
            if (m.group(5) != null) {
                String block = block(script, m.end() - 1);
                Matcher n = NARROWING.matcher(block);
                while (n.find()) {
                    String pattern = first(n.group(2), n.group(3));
                    if (pattern == null) continue;
                    (n.group(1).equals("include") ? includes : excludes).add(pattern);
                }
            }
            out.add(new RelocationRules.Relocation(from, to, false, includes, excludes));
        }
        return out;
    }

    /** The text between the brace at {@code open} and its match; to the end of the script when unbalanced. */
    private static String block(String script, int open) {
        int depth = 0;
        for (int i = open; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return script.substring(open + 1, i);
        }
        return script.substring(open + 1);
    }

    private static @Nullable String first(@Nullable String a, @Nullable String b) {
        return a != null ? a : b;
    }
}
