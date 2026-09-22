// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

/**
 * A Java binary name safe to splice into a generated launcher. The scripts are shell, so a main
 * class is one argv token: letters, digits, and the {@code . _ $} of a binary name.
 */
final class JavaMain {

    private JavaMain() {}

    /** {@code mainClass} when it is a binary name; otherwise an exception naming it. */
    static String require(String mainClass) {
        if (!isBinaryName(mainClass)) {
            throw new IllegalArgumentException("main class is not a Java binary name: " + mainClass);
        }
        return mainClass;
    }

    static boolean isBinaryName(String mainClass) {
        if (mainClass == null || mainClass.isBlank()) return false;
        if (mainClass.charAt(0) == '.' || mainClass.charAt(mainClass.length() - 1) == '.') return false;
        for (int i = 0; i < mainClass.length(); i++) {
            char c = mainClass.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.')) return false;
        }
        return true;
    }
}
