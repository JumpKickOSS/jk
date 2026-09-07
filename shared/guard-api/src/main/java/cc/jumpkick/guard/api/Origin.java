// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.Fingerprints;
import cc.jumpkick.guard.facts.MethodFacts;
import org.jspecify.annotations.Nullable;

/**
 * The class and member a bytecode site is in.
 *
 * @param cls the class as the facts index has it
 * @param member the method, or {@code null} for a class-level site (a field initialiser, an annotation)
 */
public record Origin(ClassFacts cls, @Nullable MethodFacts member) {

    /** Whether the class is in {@code pkg} or a subpackage of it ({@code cc.jumpkick.jsonl} matches {@code cc.jumpkick.jsonl.io}). */
    public boolean inPackage(String pkg) {
        String own = cls.packageName();
        return own.equals(pkg) || own.startsWith(pkg + ".");
    }

    /**
     * The source file as a source-root-relative path ({@code cc/jumpkick/jsonl/Jsonl.java}), from the
     * class file's SourceFile attribute — or, when the compiler recorded none, the outermost class's
     * name with {@code .java}, which is where Java puts it.
     */
    public String sourceFile() {
        String file = cls.sourceFile();
        if (file == null) {
            String outer = Descriptors.outermost(cls.name());
            file = outer.substring(outer.lastIndexOf('/') + 1) + ".java";
        }
        String pkg = cls.packageName().replace('.', '/');
        return pkg.isEmpty() ? file : pkg + "/" + file;
    }

    /** {@code Class#member(desc)} with anonymous ordinals and lambda counters folded. */
    public String key() {
        String base = cls.binaryName() + (member == null ? "" : "#" + member.member());
        return Fingerprints.normalise(base);
    }
}
