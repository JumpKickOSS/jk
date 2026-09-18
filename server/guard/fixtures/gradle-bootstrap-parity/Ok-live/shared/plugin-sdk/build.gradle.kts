// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
}

// Published as `cc.jumpkick:jk-plugin-sdk` at the tree's version, read from the root jk.toml.
group = "cc.jumpkick"
version = JkTreeVersion.of(rootProject.projectDir)
