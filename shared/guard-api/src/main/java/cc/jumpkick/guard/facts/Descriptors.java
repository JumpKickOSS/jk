// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

/** Descriptor and internal-name spelling helpers shared by the index and the rule matchers. */
public final class Descriptors {

    private Descriptors() {}

    /** {@code a.b.C} for {@code a/b/C}. */
    public static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }

    /** {@code a/b/C} for {@code a.b.C}. */
    public static String internalName(String binaryName) {
        return binaryName.replace('.', '/');
    }

    /** {@code a.b.C} for {@code La/b/C;}; array and primitive descriptors are returned as written. */
    public static String typeName(String desc) {
        if (desc.length() > 2 && desc.charAt(0) == 'L' && desc.charAt(desc.length() - 1) == ';') {
            return binaryName(desc.substring(1, desc.length() - 1));
        }
        return desc;
    }

    /** {@code a.b} for {@code a/b/C}; empty for the default package. */
    public static String packageOf(String internalName) {
        int i = internalName.lastIndexOf('/');
        return i < 0 ? "" : binaryName(internalName.substring(0, i));
    }

    /** The outermost class of {@code a/b/C$D$1}: {@code a/b/C}. */
    public static String outermost(String internalName) {
        int i = internalName.indexOf('$');
        return i < 0 ? internalName : internalName.substring(0, i);
    }
}
