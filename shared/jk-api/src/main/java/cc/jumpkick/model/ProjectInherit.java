// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Root-level project keys that may use Cargo-style {@code field.workspace = true}. {@code name} is
 * intentionally excluded — every module keeps its own artifact id.
 */
public enum ProjectInherit {
    GROUP,
    VERSION,
    JDK,
    JAVA,
    KOTLIN,
    GROOVY,
    SCALA,
    SOURCES,
    DESCRIPTION,
    M2INTEGRATION,
    M2INSTALL,
    LAYOUT
}
