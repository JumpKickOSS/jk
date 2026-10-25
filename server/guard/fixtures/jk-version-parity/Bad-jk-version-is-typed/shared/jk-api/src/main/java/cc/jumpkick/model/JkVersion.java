// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/** The jk release version, in a non-view module so any layer can read it. */
public final class JkVersion {
    private JkVersion() {}

    /** Current jk version. */
    public static final String VERSION = "1.4.0";
}
