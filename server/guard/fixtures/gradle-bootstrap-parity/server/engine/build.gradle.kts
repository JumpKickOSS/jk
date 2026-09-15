// SPDX-License-Identifier: Apache-2.0
plugins {
    application
}

// The client only spawns an engine jar whose filename version equals its own baked-in
// cc.jumpkick.model.JkVersion.VERSION, so the jar is named from the tree's one version source.
version = JkTreeVersion.of(rootProject.projectDir)
