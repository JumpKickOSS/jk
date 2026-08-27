// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.host.Os;

/**
 * External tools for {@code jk mvn}/{@code jk gradle} passthroughs: cache slug and bin names.
 */
public enum BuildTool {
    MAVEN("maven", "mvn", "mvn.cmd"),
    GRADLE("gradle", "gradle", "gradle.bat"),
    KOTLIN("kotlin", "kotlinc", "kotlinc.bat");

    private final String slug;
    private final String posixBinary;
    private final String windowsBinary;

    BuildTool(String slug, String posixBinary, String windowsBinary) {
        this.slug = slug;
        this.posixBinary = posixBinary;
        this.windowsBinary = windowsBinary;
    }

    /** Directory name under the provisioned-tools root, {@code $JK_STORE_DIR/tools/}. */
    public String slug() {
        return slug;
    }

    /** Binary name under {@code <home>/bin/} on the current OS. */
    public String binaryName() {
        return Os.isWindows() ? windowsBinary : posixBinary;
    }
}
